package com.agentcore.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
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

/** 配置了 agentcore.web.token：health 放行，其余接口要 Bearer Token。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentWebTokenTest.App.class,
        properties = {"agentcore.sessions.enabled=false", "agentcore.memory.enabled=false", "agentcore.skills.enabled=false",
                "agentcore.web.token=secret"})
class AgentWebTokenTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ModelGateway scriptedModel() {
            return new ScriptedModelGateway().otherwise(r -> ScriptedModelGateway.reply("ok"));
        }
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthIsOpenOtherEndpointsNeedToken() throws Exception {
        HttpResponse<String> health = send("GET", "/api/agent/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(Json.read(health.body()).path("model").asText()).isEqualTo("mock:scripted");

        assertThat(send("POST", "/api/agent/conversations", null).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/agent/conversations", "wrong").statusCode()).isEqualTo(401);
        HttpResponse<String> created = send("POST", "/api/agent/conversations", "secret");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(Json.read(created.body()).path("id").asText()).isNotBlank();
    }
}
