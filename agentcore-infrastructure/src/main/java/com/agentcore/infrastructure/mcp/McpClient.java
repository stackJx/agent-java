package com.agentcore.infrastructure.mcp;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** MCP JSON-RPC 客户端。 */
public final class McpClient implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final String name;
    private final McpTransport transport;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private String instructions = "";

    public McpClient(String name, McpTransport transport, Duration timeout) {
        this.name = name;
        this.transport = transport;
        this.timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
    }

    @SuppressWarnings("unchecked")
    public static McpClient fromConfig(String name, Map<String, Object> cfg, Path cwd) {
        Duration timeout = Duration.ofSeconds(Long.parseLong(String.valueOf(cfg.getOrDefault("timeout", 120))));
        McpTransport t;
        if (cfg.get("url") != null) {
            t = new HttpTransport(String.valueOf(cfg.get("url")), (Map<String, String>) cfg.get("headers"), timeout);
        } else if (cfg.get("command") != null) {
            List<String> args = cfg.get("args") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            Path dir = cfg.get("cwd") != null ? cwd.resolve(String.valueOf(cfg.get("cwd"))) : cwd;
            t = new StdioTransport(String.valueOf(cfg.get("command")), args, (Map<String, String>) cfg.get("env"), dir);
        } else {
            throw new IllegalArgumentException("MCP server '" + name + "' needs either 'command' or 'url'");
        }
        return new McpClient(name, t, timeout);
    }

    public String name() {
        return name;
    }

    public String instructions() {
        return instructions;
    }

    public void start() throws Exception {
        transport.start(this::onMessage, this::onClose);
        ObjectNode params = Json.object().put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "agentcore").put("version", "0.1.0");
        JsonNode result = request("initialize", params);
        instructions = result.path("instructions").asText("");
        transport.protocolVersion(result.path("protocolVersion").asText(PROTOCOL_VERSION));
        notify("notifications/initialized", null);
    }

    public JsonNode request(String method, JsonNode params) throws Exception {
        if (closed) {
            throw new McpException("MCP server '" + name + "' is closed");
        }
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pending.put(id, answer);
        ObjectNode message = Json.object().put("jsonrpc", "2.0").put("id", id).put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        try {
            transport.send(message);
            JsonNode reply = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (reply.has("error")) {
                JsonNode error = reply.get("error");
                throw new McpException(name + ": " + method + " failed: " + error.path("message").asText() + " (code "
                        + error.path("code").asText() + ")");
            }
            return reply.path("result");
        } catch (TimeoutException e) {
            throw new McpException("MCP request " + method + " to '" + name + "' timed out after " + timeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            throw new McpException(String.valueOf(e.getCause()));
        } finally {
            pending.remove(id);
        }
    }

    public void notify(String method, JsonNode params) throws Exception {
        ObjectNode message = Json.object().put("jsonrpc", "2.0").put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        transport.send(message);
    }

    /** 收到的消息：对我方请求的回复 → 交给等待者；服务端发来的请求 → 回复（只支持 ping 和 roots/list）。 */
    private void onMessage(JsonNode message) {
        if (message.has("id") && (message.has("result") || message.has("error"))) {
            CompletableFuture<JsonNode> answer = pending.get(message.get("id").asLong());
            if (answer != null) {
                answer.complete(message);
            }
            return;
        }
        String method = message.path("method").asText(null);
        if (method != null && message.has("id")) { // 服务端 → 客户端请求
            ObjectNode reply = Json.object().put("jsonrpc", "2.0");
            reply.set("id", message.get("id"));
            switch (method) {
                case "ping" -> reply.putObject("result");
                case "roots/list" -> reply.putObject("result").putArray("roots");
                default -> reply.putObject("error").put("code", -32601).put("message", "method not supported: " + method);
            }
            Thread.ofVirtual().start(() -> {
                try {
                    transport.send(reply);
                } catch (Exception ignored) {
                    // 服务端已断开
                }
            });
        }
        // 其他通知（例如 notifications/tools/list_changed）：工具列表在启动时确定，暂不支持运行中热更新，忽略
    }

    private void onClose() {
        closed = true;
        ObjectNode error = Json.object();
        error.putObject("error").put("code", -32000).put("message", "MCP server closed the connection" + transport.diagnostics());
        pending.values().forEach(answer -> answer.complete(error));
    }

    public List<JsonNode> listTools() throws Exception {
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = Json.object();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode res = request("tools/list", params);
            res.path("tools").forEach(tools::add);
            cursor = res.hasNonNull("nextCursor") ? res.get("nextCursor").asText() : null;
        } while (cursor != null);
        return tools;
    }

    public JsonNode callTool(String tool, Map<String, Object> arguments) throws Exception {
        ObjectNode params = Json.object().put("name", tool);
        params.set("arguments", Json.tree(arguments));
        return request("tools/call", params);
    }

    @Override
    public void close() {
        closed = true;
        transport.close();
    }
}
