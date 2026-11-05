package com.agentcore.infrastructure.mcp;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/** Streamable HTTP 传输（MCP 2025-03-26 及之后）：每条消息一个 POST，响应可以是 JSON 或 SSE。 */
public final class HttpTransport implements McpTransport {

    private static final int MAX_ERROR_CHARS = 500;

    private final URI url;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private volatile String sessionId;
    private volatile String protocolVersion;
    private Consumer<JsonNode> onMessage;

    public HttpTransport(String url, Map<String, String> headers, Duration timeout) {
        this.url = URI.create(url);
        this.headers = headers == null ? Map.of() : headers;
        this.timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
    }

    @Override
    public void start(Consumer<JsonNode> onMessage, Runnable onClose) {
        this.onMessage = onMessage;
    }

    @Override
    public void protocolVersion(String version) {
        this.protocolVersion = version;
    }

    private HttpRequest.Builder base() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url).timeout(timeout);
        headers.forEach(builder::header);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        return builder;
    }

    /**
     * 每条消息一个 POST。服务端可以回 202（通知，无响应体）、普通 JSON，或者一个 SSE 流（期间可能夹带服务端请求）。
     * HTTP 错误转成一条 JSON-RPC 错误回复，让等待这个 id 的请求立即失败。
     */
    @Override
    public void send(JsonNode message) throws IOException, InterruptedException {
        HttpRequest request = base().header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(message))).build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        response.headers().firstValue("mcp-session-id").ifPresent(id -> sessionId = id);
        try (InputStream body = response.body()) {
            if (response.statusCode() == 202) {
                return;
            }
            if (response.statusCode() >= 400) {
                replyWithError(message, response.statusCode(), new String(body.readAllBytes(), StandardCharsets.UTF_8));
                return;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (contentType.contains("text/event-stream")) {
                readEventStream(body);
            } else {
                String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                if (!text.isBlank()) {
                    dispatch(text);
                }
            }
        }
    }

    private void replyWithError(JsonNode message, int status, String body) {
        if (!message.has("id")) {
            return; // 通知没人等回复
        }
        ObjectNode error = Json.object().put("jsonrpc", "2.0");
        error.set("id", message.get("id"));
        error.putObject("error").put("code", -32000)
                .put("message", "HTTP " + status + ": " + body.substring(0, Math.min(MAX_ERROR_CHARS, body.length())));
        onMessage.accept(error);
    }

    /** SSE：连续的 data 行拼成一个事件，空行表示事件结束。 */
    private void readEventStream(InputStream body) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).stripLeading());
            } else if (line.isEmpty() && !data.isEmpty()) {
                dispatch(data.toString());
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) {
            dispatch(data.toString());
        }
    }

    /** 一条消息或一批消息（数组）；不是 JSON 就丢掉。 */
    private void dispatch(String text) {
        JsonNode message;
        try {
            message = Json.read(text);
        } catch (RuntimeException notJson) {
            return;
        }
        if (message.isArray()) {
            message.forEach(onMessage);
        } else {
            onMessage.accept(message);
        }
    }

    @Override
    public void close() {
        if (sessionId != null) {
            try {
                http.send(base().DELETE().build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // 尽力而为
            }
        }
    }
}
