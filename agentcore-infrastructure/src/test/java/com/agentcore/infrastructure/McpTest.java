package com.agentcore.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.mcp.McpServerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class McpTest {

    @Test
    void stdioServerToolsWithPaginationAndErrors() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Map<String, Map<String, Object>> cfg = Map.of(
                "fake", Map.of("command", java, "args", List.of("-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName())),
                "broken", Map.of("command", "/nonexistent/binary"));
        try (McpServerManager mgr = new McpServerManager(cfg, Path.of("."))) {
            List<Tool> tools = mgr.start();
            assertEquals(List.of("mcp__fake__add", "mcp__fake__echo", "mcp__fake__fail"), tools.stream().map(Tool::name).toList());
            assertTrue(mgr.errors().containsKey("broken"));
            Tool echo = tools.get(1);
            assertTrue(echo.readOnly());
            assertEquals(PermissionLevel.ASK, echo.defaultPermission(), "服务器自称只读不等于免审批");
            assertTrue(mgr.promptSection().contains("Use echo for testing."));

            ToolOutput o1 = echo.execute(Map.of("text", "hi"), null);
            assertEquals("echo: hi", ((TextBlock) o1.content().get(0)).text());
            ToolOutput o2 = tools.get(0).execute(Map.of("a", 1, "b", 2), null);
            assertTrue(o2.text().contains("\"sum\" : 3.0"), o2.text());
            ToolOutput o3 = tools.get(2).execute(Map.of(), null);
            assertTrue(o3.isError());
        }
    }

    @Test
    void streamableHttpKeepsSessionId() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/mcp", ex -> {
            String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if (ex.getRequestMethod().equals("DELETE")) {
                calls.add("DELETE:" + sid);
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            JsonNode msg = Json.read(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String method = msg.path("method").asText();
            calls.add(method + ":" + sid);
            if (!msg.has("id")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            String body;
            if (method.equals("initialize")) {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
                body = "{\"jsonrpc\":\"2.0\",\"id\":" + msg.get("id") + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}}}}";
            } else {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                String result = method.equals("tools/list")
                        ? "{\"tools\":[{\"name\":\"hello\",\"inputSchema\":{\"type\":\"object\"}}]}"
                        : "{\"content\":[{\"type\":\"text\",\"text\":\"hello from http\"}]}";
                body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + msg.get("id") + ",\"result\":" + result + "}\n\n";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        srv.start();
        try {
            McpServerManager mgr = new McpServerManager(Map.of("web",
                    Map.of("url", "http://127.0.0.1:" + srv.getAddress().getPort() + "/mcp")), Path.of("."));
            List<Tool> tools = mgr.start();
            assertEquals(List.of("mcp__web__hello"), tools.stream().map(Tool::name).toList(), mgr.errors().toString());
            assertEquals("hello from http", tools.get(0).execute(Map.of(), null).text());
            mgr.close();
            assertEquals("initialize:null", calls.get(0));
            assertTrue(calls.subList(1, calls.size()).stream().allMatch(c -> c.endsWith(":sess-1")), calls.toString());
            assertEquals("DELETE:sess-1", calls.get(calls.size() - 1));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void readOnlyHintSkipsApprovalOnlyForTrustedServers() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Map<String, Object> trusted = new LinkedHashMap<>();
        trusted.put("command", java);
        trusted.put("args", List.of("-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName()));
        trusted.put("trust_annotations", true);
        try (McpServerManager mgr = new McpServerManager(Map.of("trusted", trusted), Path.of("."))) {
            Tool echo = mgr.start().stream().filter(tool -> tool.name().equals("mcp__trusted__echo")).findFirst().orElseThrow();
            assertEquals(PermissionLevel.ALLOW, echo.defaultPermission());
        }
    }
}
