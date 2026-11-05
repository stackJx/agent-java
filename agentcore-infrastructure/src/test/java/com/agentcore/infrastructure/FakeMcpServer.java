package com.agentcore.infrastructure;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 一个最小的 stdio MCP 服务器（测试中作为子进程启动）。 */
public final class FakeMcpServer {

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            JsonNode msg = Json.read(line);
            String method = msg.path("method").asText(null);
            JsonNode id = msg.get("id");
            if ("initialize".equals(method)) {
                ObjectNode r = Json.object().put("protocolVersion", msg.path("params").path("protocolVersion").asText())
                        .put("instructions", "Use echo for testing.");
                r.putObject("capabilities").putObject("tools");
                r.putObject("serverInfo").put("name", "fake").put("version", "1");
                send(result(id, r));
            } else if ("notifications/initialized".equals(method)) {
                send(Json.object().put("jsonrpc", "2.0").put("id", "srv-1").put("method", "ping")); // 服务端 → 客户端请求
            } else if ("tools/list".equals(method)) {
                ObjectNode r = Json.object();
                ArrayNode tools = r.putArray("tools");
                if (!msg.path("params").has("cursor")) {
                    ObjectNode echo = tools.addObject().put("name", "echo").put("description", "Echo text back");
                    echo.set("inputSchema", Json.read("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"));
                    echo.putObject("annotations").put("readOnlyHint", true);
                    ObjectNode add = tools.addObject().put("name", "add").put("description", "Add numbers");
                    add.set("inputSchema", Json.read("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}}}"));
                    r.put("nextCursor", "p2");
                } else {
                    tools.addObject().put("name", "fail").put("description", "Always fails").set("inputSchema", Json.read("{\"type\":\"object\"}"));
                }
                send(result(id, r));
            } else if ("tools/call".equals(method)) {
                String name = msg.path("params").path("name").asText();
                JsonNode a = msg.path("params").path("arguments");
                ObjectNode r = Json.object();
                switch (name) {
                    case "echo" -> r.putArray("content").addObject().put("type", "text").put("text", "echo: " + a.path("text").asText());
                    case "add" -> {
                        r.putArray("content");
                        r.putObject("structuredContent").put("sum", a.path("a").asDouble() + a.path("b").asDouble());
                    }
                    default -> {
                        r.putArray("content").addObject().put("type", "text").put("text", "it broke");
                        r.put("isError", true);
                    }
                }
                send(result(id, r));
            } else if (id != null && msg.has("result")) {
                System.err.println("got pong");
            }
        }
    }

    static ObjectNode result(JsonNode id, JsonNode r) {
        ObjectNode o = Json.object().put("jsonrpc", "2.0");
        o.set("id", id);
        o.set("result", r);
        return o;
    }

    static synchronized void send(JsonNode n) {
        System.out.println(Json.write(n));
        System.out.flush();
    }
}
