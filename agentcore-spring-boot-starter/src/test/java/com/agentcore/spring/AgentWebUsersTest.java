package com.agentcore.spring;

import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.call;
import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.reply;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

/** 每个用户一个 token：只能看到、操作自己的会话和审批；共享 token 是管理员，能看到全部。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentWebUsersTest.App.class,
        properties = {"agentcore.sessions.enabled=false", "agentcore.memory.enabled=false", "agentcore.skills.enabled=false",
                "agentcore.web.token=admin-token",
                "agentcore.web.user-tokens.alice=alice-token",
                "agentcore.web.user-tokens.bob=bob-token"})
class AgentWebUsersTest {

    static final String ALICE = "alice-token";
    static final String BOB = "bob-token";
    static final String ADMIN = "admin-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ModelGateway scriptedModel() {
            return new ScriptedModelGateway()
                    .then(reply("我来写入。", call("write_thing", "name", "x")))
                    .otherwise(request -> reply("好了。"));
        }

        @Bean
        Tool writeThing() {
            return FunctionTool.builder("write_thing").schema(Schemas.object().string("name", "", true).build())
                    .handler((args, context) -> ToolOutput.text("wrote " + args.get("name"))).build();
        }
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agent" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String create(String token, String body) throws Exception {
        return Json.read(request("POST", "/conversations", token, body).body()).path("id").asText();
    }

    List<String> listedIds(String token) throws Exception {
        List<String> ids = new ArrayList<>();
        Json.read(request("GET", "/conversations", token, null).body()).path("conversations").forEach(c -> ids.add(c.path("id").asText()));
        return ids;
    }

    @Test
    void usersOnlySeeTheirOwnConversations() throws Exception {
        String alices = create(ALICE, "{}");
        String bobs = create(BOB, "{\"metadata\":{\"owner\":\"alice\"}}"); // 想冒充 alice：不会生效

        assertThat(request("GET", "/conversations/" + alices, ALICE, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/conversations/" + alices, BOB, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/conversations/" + bobs, ALICE, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/conversations/" + alices, ADMIN, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/conversations", null, null).statusCode()).isEqualTo(401);

        assertThat(listedIds(ALICE)).contains(alices).doesNotContain(bobs);
        assertThat(listedIds(BOB)).contains(bobs).doesNotContain(alices);
        assertThat(listedIds(ADMIN)).contains(alices, bobs);

        assertThat(request("POST", "/conversations/" + alices + "/messages", BOB, "{\"text\":\"hi\"}").statusCode()).isEqualTo(404);
        assertThat(request("POST", "/conversations/" + alices + "/cancel", BOB, null).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/conversations/" + alices, BOB, null).statusCode()).isEqualTo(404);
        assertThat(Json.read(request("DELETE", "/conversations/" + alices, ALICE, null).body()).path("deleted").asBoolean()).isTrue();
        assertThat(request("GET", "/conversations/" + alices, ALICE, null).statusCode()).isEqualTo(404);
    }

    @Test
    void usersCanOnlyAnswerApprovalsInTheirOwnConversations() throws Exception {
        String id = create(ALICE, "{}");
        HttpResponse<Stream<String>> stream = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agent/conversations/" + id + "/messages"))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer " + ALICE)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"写个文件\"}")).build(),
                HttpResponse.BodyHandlers.ofLines());
        String event = null;
        boolean approved = false;
        Iterator<String> lines = stream.body().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:") && "approval_required".equals(event)) {
                String requestId = Json.read(line.substring("data:".length()).strip()).path("request_id").asText();
                assertThat(Json.read(request("GET", "/approvals", BOB, null).body()).path("approvals").size()).isZero();
                assertThat(request("POST", "/approvals/" + requestId, BOB, "{\"decision\":\"allow\"}").statusCode()).isEqualTo(404);
                assertThat(Json.read(request("GET", "/approvals", ALICE, null).body()).path("approvals").size()).isEqualTo(1);
                JsonNode answer = Json.read(request("POST", "/approvals/" + requestId, ALICE, "{\"decision\":\"allow\"}").body());
                approved = answer.path("resolved").asBoolean();
            }
        }
        assertThat(approved).isTrue();
    }
}
