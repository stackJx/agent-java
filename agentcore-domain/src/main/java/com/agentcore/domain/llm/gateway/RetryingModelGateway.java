package com.agentcore.domain.llm.gateway;

import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.MalformedOutputException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.domain.shared.util.Texts;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试装饰器：包在任意 {@link ModelGateway} 外面，让一次模型调用"尽量成功"。
 *
 * <ul>
 *   <li>可重试的错误（{@link ModelException#retryable()}：限流 / 5xx / 网络）：等 {@code baseDelay × 2^n + 0～1 秒随机抖动}
 *       （最多 {@value #MAX_BACKOFF_SECONDS} 秒）后重试；服务端给了 Retry-After 就按它等（最多 {@value #MAX_RETRY_AFTER_SECONDS} 秒）。
 *       最多重试 {@code maxRetries} 次，等待期间可以被取消；</li>
 *   <li>输出无法解析（{@link MalformedOutputException}）：马上重发，最多 {@code maxMalformedRetries} 次。
 *       工具调用的 ID 为空或重复（结果没法按 ID 回填）也算这一类；</li>
 *   <li>上下文超窗（{@link ContextOverflowException}）、不可重试的错误、取消：直接抛给调用方。</li>
 * </ul>
 * 每次重试都会通过 {@link ModelStreamListener#onRetry} / {@link ModelStreamListener#onMalformedOutput} 通知调用方。
 * 注意：失败前可能已经流出了一部分文字，重试会从头重新生成，界面收到通知后应丢弃这一轮已经显示的部分。
 *
 * <p>引擎内部按 EngineSettings 自动套上这一层；需要追踪、监控时，可以照同样的方式再包一层装饰器。
 */
public final class RetryingModelGateway implements ModelGateway {

    /** 指数退避的上限：重试次数再多，两次之间也最多等这么久。 */
    static final long MAX_BACKOFF_SECONDS = 60;
    /** 服务端 Retry-After 的上限，防止异常的头部让运行挂上几个小时。 */
    static final long MAX_RETRY_AFTER_SECONDS = 300;
    /** 随机抖动的范围（毫秒），避免大量客户端同时重试。 */
    private static final long JITTER_MILLIS = 1000;
    /** 2 的多少次方以后就肯定超过上限了；限制移位次数，避免 1L &lt;&lt; 63 溢出。 */
    private static final int MAX_BACKOFF_EXPONENT = 20;

    private final ModelGateway delegate;
    private final int maxRetries;
    private final Duration baseDelay;
    private final int maxMalformedRetries;

    public RetryingModelGateway(ModelGateway delegate, int maxRetries, Duration baseDelay, int maxMalformedRetries) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxMalformedRetries = maxMalformedRetries;
    }

    public ModelGateway delegate() {
        return delegate;
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public int contextWindow() {
        return delegate.contextWindow();
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
        int malformed = 0;
        int attempt = 0;
        while (true) {
            cancel.throwIfCancelled();
            try {
                return requireUsableToolCallIds(delegate.stream(request, listener, cancel));
            } catch (CancelledException | ContextOverflowException e) {
                throw e;
            } catch (MalformedOutputException e) {
                if (++malformed > maxMalformedRetries) {
                    throw e;
                }
                listener.onMalformedOutput(e.getMessage());
            } catch (ModelException e) {
                if (cancel.isCancelled()) {
                    throw new CancelledException();
                }
                if (!e.retryable() || attempt >= maxRetries) {
                    throw e;
                }
                long delay = retryDelayMillis(attempt, e);
                attempt++;
                listener.onRetry(attempt, delay, Texts.clip(e.getMessage(), 300));
                sleep(delay, cancel);
            }
        }
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** 工具调用结果按 ID 回填，所以 ID 不能为空、不能重复；否则按"输出无法解析"处理，重发本轮。 */
    static ModelResponse requireUsableToolCallIds(ModelResponse response) {
        Set<String> seen = new HashSet<>();
        for (ToolCall call : response.message().toolCalls()) {
            if (call.id() == null || call.id().isBlank()) {
                throw new MalformedOutputException("tool call " + call.name() + " has no id", null);
            }
            if (!seen.add(call.id())) {
                throw new MalformedOutputException("tool call id " + call.id() + " is used more than once", null);
            }
        }
        return response;
    }

    /** 第 attempt 次重试前等多久：有 Retry-After 用它，否则指数退避加抖动；两者都有上限。 */
    long retryDelayMillis(int attempt, ModelException failure) {
        if (failure.retryAfter() != null && !failure.retryAfter().isNegative()) {
            return Math.min(failure.retryAfter().toMillis(), MAX_RETRY_AFTER_SECONDS * 1000);
        }
        long backoff = baseDelay.toMillis() * (1L << Math.min(attempt, MAX_BACKOFF_EXPONENT));
        return Math.min(backoff, MAX_BACKOFF_SECONDS * 1000) + ThreadLocalRandom.current().nextLong(0, JITTER_MILLIS);
    }

    /** 分段睡眠，每 100 毫秒检查一次取消。 */
    private static void sleep(long millis, CancellationToken cancel) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            cancel.throwIfCancelled();
            try {
                Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
    }
}
