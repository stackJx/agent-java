package com.agentcore.spring;

import static org.assertj.core.api.Assertions.*;

import com.agentcore.application.AgentService;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.infrastructure.json.Json;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.interfaces.web.ModelCatalog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AgentWebModelsTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
        @Bean ModelGateway scriptedModel() {
            return new ScriptedModelGateway().otherwise(r -> ScriptedModelGateway.reply("startup-only"));
        }
    }
    private ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(App.class).properties(Map.of(
                "server.port", "0", "spring.main.banner-mode", "off", "agentcore.sessions.enabled", "false",
                "agentcore.memory.enabled", "false", "agentcore.skills.enabled", "false",
                "agentcore.agent.project-instructions", "false", "agentcore.web.models.file", "",
                "agentcore.web.base-path", "/custom/api")).run(args);
    }
    private HttpResponse<String> request(HttpClient http, ConfigurableApplicationContext app,
                                         String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + app.getEnvironment().getProperty("local.server.port") + "/custom/api" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void authorizedUsersDiscoverAndSelectButOnlyAdminManages() throws Exception {
        try (var app = start("--agentcore.web.token=admin", "--agentcore.web.user-tokens.alice=user");
             var http = HttpClient.newHttpClient()) {
            assertThat(request(http, app, "GET", "/models", null, null).statusCode()).isEqualTo(401);
            var discovery = request(http, app, "GET", "/models", "user", null);
            assertThat(discovery.statusCode()).isEqualTo(200);
            assertThat(Json.read(discovery.body()).path("can_manage").asBoolean()).isFalse();
            assertThat(discovery.body()).contains("server-default", "mock").doesNotContain("base_url", "api_key");
            assertThat(request(http, app, "GET", "/model-settings", "user", null).statusCode()).isEqualTo(403);
            assertThat(request(http, app, "PUT", "/model-settings/custom", "user", "{}").statusCode()).isEqualTo(403);
            assertThat(request(http, app, "DELETE", "/model-settings/custom", "user", null).statusCode()).isEqualTo(403);
            assertThat(request(http, app, "PUT", "/model-settings/default", "user", "{\"model_id\":\"mock\"}").statusCode()).isEqualTo(403);
            String profile = "{\"label\":\"Offline custom\",\"type\":\"mock\",\"model\":\"mock\"}";
            assertThat(request(http, app, "PUT", "/model-settings/custom", "admin", profile).statusCode()).isEqualTo(200);
            assertThat(Json.read(request(http, app, "GET", "/models", "admin", null).body()).path("can_manage").asBoolean()).isTrue();
            String id = Json.read(request(http, app, "POST", "/conversations", "user",
                    "{\"metadata\":{\"private_note\":\"do-not-expose\"}}").body()).path("id").asText();
            var service = app.getBean(AgentService.class);
            assertThat(request(http, app, "POST", "/conversations/" + id + "/messages", "user",
                    "{\"text\":\"hello\",\"model_id\":\"unregistered\"}").statusCode()).isEqualTo(404);
            assertThat(service.find(id).orElseThrow().messages()).isEmpty();
            assertThat(service.isRunning(id)).isFalse();
            var reply = request(http, app, "POST", "/conversations/" + id + "/messages", "user",
                    "{\"text\":\"hello\",\"model_id\":\"custom\"}");
            assertThat(reply.statusCode()).isEqualTo(200);
            assertThat(reply.body()).contains("run_finished", "mock").doesNotContain("startup-only");
            assertThat(service.find(id).orElseThrow().metadata()).containsEntry("model_id", "custom");
            // Actual HTTP detail must expose the run selection, not silently substitute the catalog default.
            assertThat(Json.read(request(http, app, "GET", "/models", "user", null).body())
                    .path("default_id").asText()).isEqualTo("server-default");
            var detail = request(http, app, "GET", "/conversations/" + id, "user", null);
            assertThat(detail.statusCode()).isEqualTo(200);
            var metadata = Json.read(detail.body()).path("metadata");
            assertThat(metadata.path("model_id").asText()).isEqualTo("custom");
            assertThat(metadata.path("model").asText()).isEqualTo(service.find(id).orElseThrow().metadata().get("model"));
            assertThat(metadata.size()).isEqualTo(2);
            assertThat(detail.body()).doesNotContain("private_note", "do-not-expose");
            assertThat(request(http, app, "PUT", "/model-settings/default", "admin", "{\"model_id\":\"mock\"}").statusCode()).isEqualTo(200);
            assertThat(Json.read(request(http, app, "GET", "/conversations/" + id, "user", null).body())
                    .path("metadata").path("model_id").asText()).isEqualTo("custom");
            assertThat(request(http, app, "POST", "/conversations/" + id + "/messages", "user", "{\"text\":\"again\"}").body())
                    .contains("mock").doesNotContain("startup-only");
            assertThat(service.find(id).orElseThrow().metadata()).containsEntry("model_id", "mock");
            assertThat(Json.read(request(http, app, "GET", "/conversations/" + id, "user", null).body())
                    .path("metadata").path("model_id").asText()).isEqualTo("mock");
            // Health retains its original startup-gateway meaning, regardless of the web default.
            assertThat(Json.read(request(http, app, "GET", "/health", null, null).body()).path("model").asText())
                    .isEqualTo(service.engine().model().describe());
        }
    }
    @Test void settingsAreRedactedAndInvalidRequestsAlwaysReturnJson() throws Exception {
        try (var app = start("--agentcore.web.token=admin"); var http = HttpClient.newHttpClient()) {
            String profile = "{\"label\":\"Private\",\"type\":\"openai\",\"model\":\"model-x\","
                    + "\"base_url\":\"http://localhost:12345/v1\",\"api_key\":\"sensitive-key\"}";
            var saved = request(http, app, "PUT", "/model-settings/local", "admin", profile);
            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(saved.body()).contains("has_api_key").doesNotContain("sensitive-key", "\"api_key\"");
            assertThat(request(http, app, "GET", "/models", "admin", null).body()).doesNotContain("localhost", "sensitive-key");
            for (String body : new String[] {"{sensitive-key", "null", "[]", "{}", "{\"type\":false}"}) {
                var error = request(http, app, "PUT", "/model-settings/local", "admin", body);
                assertThat(error.statusCode()).isEqualTo(400);
                assertThat(error.headers().firstValue("content-type").orElse("")).startsWith("application/json");
                assertThat(Json.read(error.body()).has("error")).isTrue();
                assertThat(error.body()).doesNotContain("sensitive-key");
            }
            var changed = request(http, app, "PUT", "/model-settings/local", "admin",
                    "{\"type\":\"openai\",\"model\":\"model-x\",\"base_url\":\"http://other.invalid/v1\"}");
            assertThat(changed.statusCode()).isEqualTo(400);
            assertThat(changed.body()).doesNotContain("sensitive-key");
        }
    }
    @Test void localOnlyFalseNeverGrantsAnonymousAdministration() throws Exception {
        try (var app = start("--agentcore.web.local-only=false"); var http = HttpClient.newHttpClient()) {
            assertThat(request(http, app, "GET", "/models", null, null).statusCode()).isEqualTo(200);
            assertThat(Json.read(request(http, app, "GET", "/models", null, null).body()).path("can_manage").asBoolean()).isFalse();
            assertThat(request(http, app, "GET", "/model-settings", null, null).statusCode()).isEqualTo(403);
            assertThat(request(http, app, "PUT", "/model-settings/default", null, "{\"model_id\":\"mock\"}").statusCode()).isEqualTo(403);
        }
    }
    @Test void verifiedLocalModeCanManageAndDisabledFeaturePreservesLegacySend() throws Exception {
        try (var app = start(); var http = HttpClient.newHttpClient()) {
            assertThat(request(http, app, "GET", "/model-settings", null, null).statusCode()).isEqualTo(200);
            assertThat(Json.read(request(http, app, "GET", "/models", null, null).body()).path("can_manage").asBoolean()).isTrue();
        }
        try (var app = start("--agentcore.web.models.enabled=false"); var http = HttpClient.newHttpClient()) {
            assertThat(app.getBeansOfType(ModelCatalog.class)).isEmpty();
            assertThat(request(http, app, "GET", "/models", null, null).statusCode()).isEqualTo(404);
            String id = Json.read(request(http, app, "POST", "/conversations", null, "{}").body()).path("id").asText();
            assertThat(request(http, app, "POST", "/conversations/" + id + "/messages", null,
                    "{\"text\":\"hello\"}").body()).contains("startup-only");
            assertThat(request(http, app, "POST", "/conversations/" + id + "/messages", null,
                    "{\"text\":\"hello\",\"model_id\":\"mock\"}").statusCode()).isEqualTo(400);
        }
    }
}
