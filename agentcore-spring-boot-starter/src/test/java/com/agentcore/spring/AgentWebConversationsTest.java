package com.agentcore.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

/** 会话详情默认不带图片数据；列表数量有上限；可以删除会话。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentWebConversationsTest.App.class,
        properties = {"agentcore.sessions.enabled=false", "agentcore.memory.enabled=false", "agentcore.skills.enabled=false"})
class AgentWebConversationsTest {

    /** 一个 1x1 的 PNG。 */
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ModelGateway scriptedModel() {
            return new ScriptedModelGateway().otherwise(request -> ScriptedModelGateway.reply("看到了"));
        }
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> request(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agent" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void detailsLeaveOutImageDataUnlessAsked() throws Exception {
        String id = Json.read(request("POST", "/conversations", "{}").body()).path("id").asText();
        String message = "{\"text\":\"看图\",\"images\":[{\"media_type\":\"image/png\",\"data\":\"" + PNG + "\"}]}";
        assertThat(request("POST", "/conversations/" + id + "/messages", message).body()).contains("run_finished");

        JsonNode image = Json.read(request("GET", "/conversations/" + id, null).body()).path("messages").get(0).path("content").get(1);
        assertThat(image.path("type").asText()).isEqualTo("image");
        assertThat(image.has("data")).isFalse();
        assertThat(image.path("data_omitted").asBoolean()).isTrue();
        assertThat(image.path("bytes").asLong()).isPositive();

        JsonNode full = Json.read(request("GET", "/conversations/" + id + "?include_images=true", null).body())
                .path("messages").get(0).path("content").get(1);
        assertThat(full.path("data").asText()).isEqualTo(PNG);
    }

    @Test
    void listLimitIsCappedAndConversationsCanBeDeleted() throws Exception {
        String id = Json.read(request("POST", "/conversations", "{}").body()).path("id").asText();
        assertThat(request("GET", "/conversations?limit=1000000", null).statusCode()).isEqualTo(200);
        assertThat(Json.read(request("DELETE", "/conversations/" + id, null).body()).path("deleted").asBoolean()).isTrue();
        assertThat(request("GET", "/conversations/" + id, null).statusCode()).isEqualTo(404);
        assertThat(request("DELETE", "/conversations/" + id, null).statusCode()).isEqualTo(404);
    }
}
