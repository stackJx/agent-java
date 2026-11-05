package com.agentcore.infrastructure.mcp;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
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
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout);
        headers.forEach(b::header);
        if (sessionId != null) {
            b.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            b.header("MCP-Protocol-Version", protocolVersion);
        }
        return b;
    }

    @Override
    public void send(JsonNode message) throws IOException, InterruptedException {
        HttpRequest req = base().header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(message))).build();
        HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        r.headers().firstValue("mcp-session-id").ifPresent(s -> sessionId = s);
        try (InputStream in = r.body()) {
            if (r.statusCode() == 202) {
                return;
            }
            if (r.statusCode() >= 400) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (message.has("id")) {
                    var err = Json.object().put("jsonrpc", "2.0");
                    err.set("id", message.get("id"));
                    err.putObject("error").put("code", -32000).put("message", "HTTP " + r.statusCode() + ": "
                            + body.substring(0, Math.min(500, body.length())));
                    onMessage.accept(err);
                }
                return;
            }
            String ctype = r.headers().firstValue("content-type").orElse("");
            if (ctype.contains("text/event-stream")) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).stripLeading());
                    } else if (line.isEmpty() && !data.isEmpty()) {
                        dispatch(data.toString());
                        data.setLength(0);
                    }
                }
                if (!data.isEmpty()) {
                    dispatch(data.toString());
                }
            } else {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (!body.isBlank()) {
                    dispatch(body);
                }
            }
        }
    }

    private void dispatch(String text) {
        JsonNode n;
        try {
            n = Json.read(text);
        } catch (RuntimeException e) {
            return;
        }
        if (n.isArray()) {
            n.forEach(onMessage);
        } else {
            onMessage.accept(n);
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
