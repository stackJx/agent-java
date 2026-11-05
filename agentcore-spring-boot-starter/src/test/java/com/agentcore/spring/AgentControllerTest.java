package com.agentcore.spring;

import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.call;
import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.reply;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.domain.model.ModelGateway;
import com.agentcore.domain.tool.FunctionTool;
import com.agentcore.domain.tool.Schemas;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolOutput;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentControllerTest.App.class,
        properties = {"agentcore.sessions.enabled=false", "agentcore.memory.enabled=false", "agentcore.skills.enabled=false",
                "agentcore.web.base-path=/agent", "spring.threads.virtual.enabled=true"})
class AgentControllerTest {

    static final AtomicInteger WRITES = new AtomicInteger();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ModelGateway scriptedModel() {
            return new ScriptedModelGateway()
                    .then(reply("我来写入。", call("write_thing", "name", "x")))
                    .then(reply("写好了。"));
        }

        @Bean
        Tool writeThing() {
            return FunctionTool.builder("write_thing").schema(Schemas.object().string("name", "", true).build())
                    .handler((a, c) -> {
                        WRITES.incrementAndGet();
                        return ToolOutput.text("wrote " + a.get("name"));
                    }).build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ModelGateway model;

    final HttpClient http = HttpClient.newHttpClient();

    JsonNode post(String path, String body) throws Exception {
        return Json.read(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void sseStreamWithWebApproval() throws Exception {
        String id = post("/agent/conversations", "{\"metadata\":{\"user\":\"u1\"}}").path("id").asText();
        assertThat(id).isNotBlank();

        HttpResponse<java.util.stream.Stream<String>> resp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/agent/conversations/" + id + "/messages"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"写个文件\"}")).build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");

        List<String> types = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String event = null;
        Iterator<String> it = resp.body().iterator();
        while (it.hasNext()) {
            String line = it.next();
            if (line.startsWith("event:")) {
                event = line.substring(6).strip();
                types.add(event);
            } else if (line.startsWith("data:")) {
                JsonNode data = Json.read(line.substring(5).strip());
                if ("approval_required".equals(event)) {
                    JsonNode pending = Json.read(http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/agent/approvals?conversationId=" + id)).GET().build(),
                            HttpResponse.BodyHandlers.ofString()).body());
                    assertThat(pending.path("approvals").size()).isEqualTo(1);
                    JsonNode ok = post("/agent/approvals/" + data.path("request_id").asText(), "{\"decision\":\"allow\"}");
                    assertThat(ok.path("resolved").asBoolean()).isTrue();
                } else if ("text_delta".equals(event)) {
                    text.append(data.path("text").asText());
                } else if ("run_finished".equals(event)) {
                    assertThat(data.path("stop_reason").asText()).isEqualTo("end_turn");
                }
            }
        }
        assertThat(WRITES.get()).isEqualTo(1);
        assertThat(text.toString()).isEqualTo("我来写入。写好了。");
        assertThat(types).contains("run_started", "approval_required", "permission_decided", "tool_started", "tool_finished", "run_finished");

        JsonNode conv = Json.read(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/agent/conversations/" + id))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        assertThat(conv.path("messages").size()).isEqualTo(4);
        assertThat(conv.path("running").asBoolean()).isFalse();
    }

    @Test
    void errorsAreJson() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/agent/conversations/nope"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(Json.read(r.body()).path("error").asText()).contains("conversation not found");
    }
}
