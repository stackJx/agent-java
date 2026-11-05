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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 两个 OpenAI 协议适配器（Chat Completions、Responses）共用的 HTTP 细节：
 * 发流式请求、逐条读 SSE 事件、翻译 HTTP 错误、取消、解析工具参数。
 */
final class OpenAiHttp {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    /** 错误信息里最多带多少字符的响应体。 */
    private static final int MAX_ERROR_BODY_CHARS = 800;
    /** 参数解析失败时，错误信息里最多带多少字符的原始参数。 */
    private static final int MAX_ARGUMENT_PREVIEW_CHARS = 200;

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
            throw httpError(response.statusCode(), readQuietly(response.body()));
        }
        return response.body();
    }

    /**
     * 逐条读 SSE 事件：每行 {@code data: {json}}，以 {@code data: [DONE]} 或连接关闭结束。
     * 每个事件交给 handler；handler 返回 false 表示已经拿到想要的结果，不再往下读。
     *
     * <p>取消时会关闭输入流打断阻塞读，然后抛 CancelledException；读流出错、事件不是合法 JSON 都抛可重试的 ModelException。
     */
    static void readEvents(InputStream body, CancellationToken cancel, Predicate<JsonNode> handler) {
        Runnable unregister = closeOnCancel(cancel, body);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue; // event: / id: / 注释行 / 空行
                }
                String data = line.substring("data:".length()).strip();
                if (data.equals("[DONE]")) {
                    break;
                }
                if (!data.isEmpty() && !handler.test(Json.read(data))) {
                    break;
                }
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new ModelException("stream interrupted: " + e.getMessage(), true, null, e);
        } catch (UncheckedIOException e) {
            throw new ModelException("invalid stream event: " + e.getMessage(), true, null, e);
        } finally {
            unregister.run();
        }
        if (cancel.isCancelled()) {
            throw new CancelledException();
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

    /** 限流、超时、5xx 可重试；400 且提示上下文太长 → 超窗（引擎会压缩后重做本轮）。 */
    static ModelException httpError(int status, String body) {
        String message = "HTTP " + status + ": " + clip(body, MAX_ERROR_BODY_CHARS);
        String lower = body.toLowerCase();
        boolean contextTooLong = lower.contains("context_length") || lower.contains("maximum context")
                || lower.contains("context window") || lower.contains("too long");
        if (status == 400 && contextTooLong) {
            return new ContextOverflowException(message, null);
        }
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        return new ModelException(message, retryable, status, null);
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
