package com.agentcore.domain.llm.gateway;

import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.MalformedOutputException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.domain.shared.util.Texts;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试装饰器：包在任意 {@link ModelGateway} 外面，让一次模型调用"尽量成功"。
 *
 * <ul>
 *   <li>可重试的错误（{@link ModelException#retryable()}：限流 / 5xx / 网络）：等 {@code baseDelay × 2^n + 0～1 秒随机抖动} 后重试，
 *       最多 {@code maxRetries} 次，等待期间可以被取消；</li>
 *   <li>输出无法解析（{@link MalformedOutputException}）：马上重发，最多 {@code maxMalformedRetries} 次；</li>
 *   <li>上下文超窗（{@link ContextOverflowException}）、不可重试的错误、取消：直接抛给调用方。</li>
 * </ul>
 * 每次重试都会通过 {@link ModelStreamListener#onRetry} / {@link ModelStreamListener#onMalformedOutput} 通知调用方。
 *
 * <p>引擎内部按 EngineSettings 自动套上这一层；需要追踪、监控时，可以照同样的方式再包一层装饰器。
 */
public final class RetryingModelGateway implements ModelGateway {

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
                return delegate.stream(request, listener, cancel);
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
                long delay = baseDelay.toMillis() * (1L << attempt) + ThreadLocalRandom.current().nextLong(0, 1000);
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
