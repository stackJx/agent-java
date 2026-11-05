package com.agentcore.interfaces;

import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.call;
import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.reply;
import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.agent.AgentDefinition;
import com.agentcore.domain.agent.AgentEngine;
import com.agentcore.domain.agent.EngineSettings;
import com.agentcore.domain.tool.FunctionTool;
import com.agentcore.domain.tool.Schemas;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolOutput;
import com.agentcore.domain.tool.ToolRegistry;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.infrastructure.persistence.InMemoryConversationRepository;
import com.agentcore.interfaces.http.AgentHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentHttpServerTest {

    final HttpClient http = HttpClient.newHttpClient();

    JsonNode post(String url, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return Json.read(http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void sseStreamWithWebApprovalFlow() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Tool write = FunctionTool.builder("write_thing").schema(Schemas.object().string("name", "", true).build())
                .handler((a, c) -> {
                    writes.incrementAndGet();
                    return ToolOutput.text("wrote " + a.get("name"));
                }).build();
        ScriptedModelGateway model = new ScriptedModelGateway()
                .then(reply("我来写入。", call("write_thing", "name", "x")))
                .then(reply("写好了。"));
        AsyncApprover approver = new AsyncApprover(Duration.ofSeconds(10));
        AgentEngine engine = AgentEngine.builder().definition(new AgentDefinition("web", "", EngineSettings.defaults(Path.of("."))))
                .model(model).tools(new ToolRegistry(List.of(write))).approver(approver).build();
        AgentService svc = new AgentService(engine, new InMemoryConversationRepository());
        String token = "secret";
        try (AgentHttpServer server = new AgentHttpServer(svc, approver, "127.0.0.1", 0, token).start()) {
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> unauth = http.send(HttpRequest.newBuilder(URI.create(base + "/api/conversations")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauth.statusCode());

            String id = post(base + "/api/conversations", "", token).path("id").asText();
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/conversations/" + id + "/messages"))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"写个文件\"}")).build();
            HttpResponse<java.util.stream.Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
            assertEquals(200, resp.statusCode());
            List<String> types = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Iterator<String> it = resp.body().iterator();
            String event = null;
            while (it.hasNext()) {
                String line = it.next();
                if (line.startsWith("event: ")) {
                    event = line.substring(7);
                    types.add(event);
                } else if (line.startsWith("data: ")) {
                    JsonNode data = Json.read(line.substring(6));
                    if ("approval_required".equals(event)) {
                        assertEquals("write_thing", data.path("tool").asText());
                        JsonNode ok = post(base + "/api/approvals/" + data.path("request_id").asText(), "{\"decision\":\"allow\"}", token);
                        assertTrue(ok.path("resolved").asBoolean());
                    } else if ("text_delta".equals(event)) {
                        text.append(data.path("text").asText());
                    } else if ("run_finished".equals(event)) {
                        assertEquals("end_turn", data.path("stop_reason").asText());
                        break;
                    }
                }
            }
            assertEquals(1, writes.get());
            assertEquals("我来写入。写好了。", text.toString());
            assertTrue(types.containsAll(List.of("run_started", "approval_required", "permission_decided", "tool_started",
                    "tool_finished", "run_finished")), types.toString());

            JsonNode conv = Json.read(http.send(HttpRequest.newBuilder(URI.create(base + "/api/conversations/" + id))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(4, conv.path("messages").size());
            assertEquals("tool_result", conv.path("messages").get(2).path("content").get(0).path("type").asText());
            assertFalse(conv.path("running").asBoolean());
        }
    }
}
