package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 两个 OpenAI 协议适配器（Chat Completions、Responses）共用的 HTTP 细节：
 * 发流式请求、逐条读 SSE 事件、翻译 HTTP 错误、取消、解析工具参数。
 *
 * <p>超时：JDK HttpClient 的请求超时只管到响应头到达为止，流开始之后不再生效。所以读流时另有一个"空闲看门狗"：
 * 超过 timeout 没收到任何数据就关掉连接，按可重试的错误处理 —— 否则对方不发数据也不断开时，运行会一直挂着。
 */
final class OpenAiHttp {

    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    /** 错误信息里最多带多少字符的响应体。 */
    private static final int MAX_ERROR_BODY_CHARS = 800;
    /** 参数解析失败时，错误信息里最多带多少字符的原始参数。 */
    private static final int MAX_ARGUMENT_PREVIEW_CHARS = 200;
    /** 看门狗多久检查一次（空闲上限很短时按上限的四分之一）。 */
    private static final long WATCHDOG_INTERVAL_MILLIS = 1000;
    private static final long MIN_WATCHDOG_INTERVAL_MILLIS = 10;
    /**
     * 400 错误里表示"输入超出上下文窗口"的说法（各家不同）。只认这些具体的说法：
     * 单看 "too long" 会把"工具描述太长"之类的参数错误也当成超窗，白白压缩再重试。
     */
    private static final List<String> CONTEXT_OVERFLOW_PHRASES = List.of(
            "context_length", "context length", "maximum context", "context window", "prompt is too long",
            "input is too long", "too many tokens", "exceeded model token limit", "range of input length");

    /** 所有流共用的看门狗线程（守护线程，不阻止 JVM 退出）。 */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "agentcore-sse-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private OpenAiHttp() {
    }

    /** 发出流式 POST 请求，返回 SSE 响应体。连接失败、HTTP 错误都翻译成引擎认识的异常。 */
    static InputStream post(HttpClient http, URI endpoint, String json, String apiKey, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout == null ? DEFAULT_TIMEOUT : timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        if (headers != null) {
            headers.forEach(request::header);
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ModelException("connection failed: " + e.getMessage(), true, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        if (response.statusCode() >= 400) {
            throw httpError(response.statusCode(), readQuietly(response.body()), retryAfter(response.headers()));
        }
        return response.body();
    }

    /**
     * 逐条读 SSE 事件：每行 {@code data: {json}}，以 {@code data: [DONE]} 或连接关闭结束。
     * 每个事件交给 handler；handler 返回 false 表示已经拿到想要的结果，不再往下读。
     *
     * <p>取消时会关闭输入流打断阻塞读，然后抛 CancelledException；读流出错、超过 idleTimeout 没有数据、
     * 事件不是合法 JSON 都抛可重试的 ModelException。
     *
     * @return 流是否正常结束（收到了 {@code [DONE]}，或 handler 表示已经拿到结果）；false 表示连接直接断开了
     */
    static boolean readEvents(InputStream body, CancellationToken cancel, Duration idleTimeout, Predicate<JsonNode> handler) {
        Runnable unregister = closeOnCancel(cancel, body);
        IdleWatchdog watchdog = new IdleWatchdog(body, idleTimeout == null ? DEFAULT_TIMEOUT : idleTimeout);
        boolean finished = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while (!finished && (line = reader.readLine()) != null) {
                watchdog.dataReceived();
                if (!line.startsWith("data:")) {
                    continue; // event: / id: / 注释行 / 空行
                }
                String data = line.substring("data:".length()).strip();
                finished = data.equals("[DONE]") || (!data.isEmpty() && !handler.test(Json.read(data)));
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            if (watchdog.fired()) {
                throw stalled(watchdog, e);
            }
            throw new ModelException("stream interrupted: " + e.getMessage(), true, null, e);
        } catch (UncheckedIOException e) {
            throw new ModelException("invalid stream event: " + e.getMessage(), true, null, e);
        } finally {
            watchdog.stop();
            unregister.run();
        }
        if (cancel.isCancelled()) {
            throw new CancelledException();
        }
        if (!finished && watchdog.fired()) {
            throw stalled(watchdog, null); // 关流有时表现为读到结尾而不是异常
        }
        return finished;
    }

    private static ModelException stalled(IdleWatchdog watchdog, Throwable cause) {
        return new ModelException("stream stalled: no data received for " + watchdog.limit().toMillis() + "ms", true, null, cause);
    }

    /** 读流时的空闲看门狗：超过 limit 没收到数据就关掉输入流，打断阻塞中的读。 */
    private static final class IdleWatchdog {
        private final InputStream stream;
        private final Duration limit;
        private final ScheduledFuture<?> check;
        private volatile long lastDataAt = System.nanoTime();
        private volatile boolean fired;

        IdleWatchdog(InputStream stream, Duration limit) {
            this.stream = stream;
            this.limit = limit;
            long interval = Math.max(MIN_WATCHDOG_INTERVAL_MILLIS, Math.min(WATCHDOG_INTERVAL_MILLIS, limit.toMillis() / 4));
            this.check = WATCHDOG.scheduleWithFixedDelay(this::closeIfIdle, interval, interval, TimeUnit.MILLISECONDS);
        }

        void dataReceived() {
            lastDataAt = System.nanoTime();
        }

        boolean fired() {
            return fired;
        }

        Duration limit() {
            return limit;
        }

        void stop() {
            check.cancel(false);
        }

        private void closeIfIdle() {
            if (!fired && System.nanoTime() - lastDataAt > limit.toNanos()) {
                fired = true;
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 关闭以中断阻塞读
                }
            }
        }
    }

    /** 取消时直接关闭输入流，阻塞中的 readLine 会立刻抛出 IOException。返回值用来注销回调。 */
    static Runnable closeOnCancel(CancellationToken cancel, InputStream in) {
        return cancel.onCancel(() -> {
            try {
                in.close();
            } catch (IOException ignored) {
                // 关闭以中断阻塞读
            }
        });
    }

    /** 限流、超时、5xx 可重试（带上服务端的 Retry-After）；400 且明确说输入超出上下文 → 超窗（引擎会压缩后重做本轮）。 */
    static ModelException httpError(int status, String body, Duration retryAfter) {
        String message = "HTTP " + status + ": " + clip(body, MAX_ERROR_BODY_CHARS);
        if (status == 400 && isContextOverflow(body)) {
            return new ContextOverflowException(message, null);
        }
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        return new ModelException(message, retryable, status, null, retryable ? retryAfter : null);
    }

    static boolean isContextOverflow(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        return CONTEXT_OVERFLOW_PHRASES.stream().anyMatch(lower::contains);
    }

    /** HTTP Retry-After：秒数或 HTTP 日期；没有或看不懂时返回 null。 */
    static Duration retryAfter(HttpHeaders headers) {
        return headers.firstValue("retry-after").map(OpenAiHttp::parseRetryAfter).orElse(null);
    }

    static Duration parseRetryAfter(String value) {
        String text = value.strip();
        try {
            return Duration.ofMillis((long) (Double.parseDouble(text) * 1000));
        } catch (NumberFormatException notSeconds) {
            try {
                Duration untilThen = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME));
                return untilThen.isNegative() ? Duration.ZERO : untilThen;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    static String newCallId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /**
     * 原始参数字符串 → ToolCall。解析失败不抛异常，而是记在 parseError 里：
     * 引擎不会执行这个调用，而是把错误回给模型让它重写。id 为空时生成一个。
     */
    static ToolCall toolCall(String id, String name, String rawArguments) {
        String raw = rawArguments == null ? "" : rawArguments;
        Map<String, Object> parsed = new LinkedHashMap<>();
        String parseError = null;
        try {
            JsonNode node = raw.isBlank() ? Json.object() : Json.read(raw);
            if (node.isObject()) {
                parsed = Json.toMap(node);
            } else {
                parseError = "tool arguments must be a JSON object, got: " + clip(raw, MAX_ARGUMENT_PREVIEW_CHARS);
            }
        } catch (RuntimeException e) {
            parseError = "invalid JSON arguments: " + clip(raw, MAX_ARGUMENT_PREVIEW_CHARS);
        }
        return new ToolCall(id != null ? id : newCallId(), name, parsed, raw, parseError);
    }

    private static String readQuietly(InputStream body) {
        try (InputStream in = body) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String clip(String text, int maxChars) {
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }
}
