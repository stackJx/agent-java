package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 两个 OpenAI 协议适配器（Chat Completions、Responses）共用的 HTTP 细节：发流式请求、翻译 HTTP 错误、取消、解析工具参数。 */
final class OpenAiHttp {

    private OpenAiHttp() {
    }

    /** 发出流式 POST 请求，返回 SSE 响应体。连接失败、HTTP 错误都翻译成引擎认识的异常。 */
    static InputStream post(HttpClient http, URI endpoint, String json, String apiKey, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .timeout(timeout == null ? Duration.ofMinutes(10) : timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        if (headers != null) {
            headers.forEach(rb::header);
        }
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ModelException("connection failed: " + e.getMessage(), true, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        if (resp.statusCode() >= 400) {
            String body;
            try (InputStream in = resp.body()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                body = "";
            }
            throw httpError(resp.statusCode(), body);
        }
        return resp.body();
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
        String msg = "HTTP " + status + ": " + (body.length() > 800 ? body.substring(0, 800) : body);
        String low = body.toLowerCase();
        if (status == 400 && (low.contains("context_length") || low.contains("maximum context") || low.contains("context window")
                || low.contains("too long"))) {
            return new ContextOverflowException(msg, null);
        }
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        return new ModelException(msg, retryable, status, null);
    }

    static String newCallId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /**
     * 原始参数字符串 → ToolCall。解析失败不抛异常，而是记在 parseError 里：
     * 引擎不会执行这个调用，而是把错误回给模型让它重写。id 为空时生成一个。
     */
    static ToolCall toolCall(String id, String name, String raw) {
        String args = raw == null ? "" : raw;
        Map<String, Object> parsed = new LinkedHashMap<>();
        String parseError = null;
        try {
            JsonNode node = args.isBlank() ? Json.object() : Json.read(args);
            if (node.isObject()) {
                parsed = Json.toMap(node);
            } else {
                parseError = "tool arguments must be a JSON object, got: " + clip(args);
            }
        } catch (RuntimeException e) {
            parseError = "invalid JSON arguments: " + clip(args);
        }
        return new ToolCall(id != null ? id : newCallId(), name, parsed, args, parseError);
    }

    private static String clip(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
