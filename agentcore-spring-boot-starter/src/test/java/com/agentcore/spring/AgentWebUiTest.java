package com.agentcore.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.interfaces.web.AgentUiController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/** 真正启动 Servlet 容器，覆盖入口、静态资源、context-path 与原有 API 访问控制。 */
class AgentWebUiTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
        @Bean
        ModelGateway scriptedModel() {
            return new ScriptedModelGateway().otherwise(r -> ScriptedModelGateway.reply("ok"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(App.class)
    @ComponentScan(basePackageClasses = AgentUiController.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AgentUiController.class))
    static class ScanningApp {}

    @Test
    void defaultUiServesRenderedIndexAndAssetsWithoutChangingLocalOnlyProtection() throws Exception {
        try (ConfigurableApplicationContext context = start(App.class); HttpClient http = HttpClient.newHttpClient()) {
            assertThat(context.getBeansOfType(AgentUiController.class)).hasSize(1);
            assertRedirect(get(http, context, "/agentcore"), "/agentcore/");
            assertThat(get(http, context, "/").statusCode()).isEqualTo(404);
            assertIndex(get(http, context, "/agentcore/"), "/api/agent");
            assertIndex(get(http, context, "/agentcore/index.html"), "/api/agent");
            assertAssets(http, context, "/agentcore");
            assertThat(get(http, context, "/agentcore/missing.js").statusCode()).isEqualTo(404);
            assertThat(get(http, context, "/agentcore/index.html/").body()).doesNotContain("__AGENTCORE_API_BASE__");
            assertThat(get(http, context, "/agentcore/%2e%2e/application.properties").statusCode()).isIn(400, 404);
            assertThat(get(http, context, "/api/agent/health", "Origin", "https://evil.example").statusCode()).isEqualTo(200);
            assertThat(get(http, context, "/api/agent/conversations", "Origin", "https://evil.example").statusCode()).isEqualTo(403);
            assertThat(get(http, context, "/api/agent/conversations").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void componentScanningDoesNotCreateDuplicateControllersOrRequireStringBeans() throws Exception {
        try (ConfigurableApplicationContext context = start(ScanningApp.class); HttpClient http = HttpClient.newHttpClient()) {
            assertThat(context.getBeansOfType(AgentUiController.class)).hasSize(1);
            assertIndex(get(http, context, "/agentcore/"), "/api/agent");
        }
    }

    @Test
    void customPathsAndContextPrefixWorkWithTrailingSlashAndTokenProtection() throws Exception {
        try (ConfigurableApplicationContext context = start(App.class,
                "--server.servlet.context-path=/workspace", "--agentcore.web.ui.path=/console/chat/",
                "--agentcore.web.base-path=/services/agent/", "--agentcore.web.ui.redirect-root=true",
                "--agentcore.web.token=secret"); HttpClient http = HttpClient.newHttpClient()) {
            String ui = "/workspace/console/chat";
            String api = "/workspace/services/agent";
            assertRedirect(get(http, context, "/workspace/"), ui + "/");
            assertRedirect(get(http, context, ui), ui + "/");
            assertIndex(get(http, context, ui + "/"), api);
            assertIndex(get(http, context, ui + "/index.html"), api);
            assertAssets(http, context, ui);
            assertThat(get(http, context, "/workspace/agentcore/").statusCode()).isEqualTo(404);
            assertThat(get(http, context, "/workspace/api/agent/health").statusCode()).isEqualTo(404);
            assertThat(get(http, context, api + "/health").statusCode()).isEqualTo(200);
            assertThat(get(http, context, api + "/conversations").statusCode()).isEqualTo(401);
            assertThat(get(http, context, api + "/conversations", "Authorization", "Bearer wrong").statusCode()).isEqualTo(401);
            assertThat(get(http, context, api + "/conversations", "Authorization", "Bearer secret").statusCode()).isEqualTo(200);
            // 未开启 Spring 的转发头处理时，不能被客户端伪造的前缀改写页面配置。
            assertIndex(get(http, context, ui + "/", "X-Forwarded-Prefix", "/evil"), api);
        }
    }

    @ParameterizedTest
    @ValueSource(classes = {App.class, ScanningApp.class})
    void uiCanBeDisabledWithoutDisablingApi(Class<?> application) throws Exception {
        try (ConfigurableApplicationContext context = start(application,
                "--agentcore.web.ui.enabled=false", "--agentcore.web.ui.redirect-root=true",
                "--agentcore.web.token=secret"); HttpClient http = HttpClient.newHttpClient()) {
            assertThat(context.getBeansOfType(AgentUiController.class)).isEmpty();
            assertUiAbsent(http, context);
            assertThat(get(http, context, "/api/agent/health").statusCode()).isEqualTo(200);
            assertThat(get(http, context, "/api/agent/conversations").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void disablingWebAlsoDisablesScannedUiAndRootRedirect() throws Exception {
        try (ConfigurableApplicationContext context = start(ScanningApp.class,
                "--agentcore.web.enabled=false", "--agentcore.web.ui.redirect-root=true");
                HttpClient http = HttpClient.newHttpClient()) {
            assertThat(context.getBeansOfType(AgentUiController.class)).isEmpty();
            assertUiAbsent(http, context);
            assertThat(get(http, context, "/api/agent/health").statusCode()).isEqualTo(404);
        }
    }

    private ConfigurableApplicationContext start(Class<?> application, String... args) {
        return new SpringApplicationBuilder(application).properties(Map.of(
                "server.port", "0", "spring.main.banner-mode", "off",
                "agentcore.sessions.enabled", "false", "agentcore.memory.enabled", "false",
                "agentcore.skills.enabled", "false", "agentcore.agent.project-instructions", "false"))
                .run(args);
    }

    private HttpResponse<String> get(HttpClient http, ConfigurableApplicationContext context, String path,
                                     String... headers) throws Exception {
        String origin = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + path)).GET();
        if (headers.length > 0) {
            request.headers(headers);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertIndex(HttpResponse<String> response, String apiBase) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-cache");
        assertThat(response.body()).contains("name=\"agentcore-api-base\" content=\"" + apiBase + "\"")
                .doesNotContain("__AGENTCORE_API_BASE__");
    }

    private void assertRedirect(HttpResponse<String> response, String target) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(URI.create(response.headers().firstValue("Location").orElseThrow()).getPath()).isEqualTo(target);
    }

    private void assertAssets(HttpClient http, ConfigurableApplicationContext context, String ui) throws Exception {
        for (String asset : new String[]{"css/app.css", "js/app.js", "js/api.js", "favicon.svg"}) {
            HttpResponse<String> response = get(http, context, ui + "/" + asset);
            assertThat(response.statusCode()).as(asset).isEqualTo(200);
            assertThat(response.body()).as(asset).isNotBlank().doesNotContain("<!doctype html>");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-cache");
        }
    }

    private void assertUiAbsent(HttpClient http, ConfigurableApplicationContext context) throws Exception {
        for (String path : new String[]{"/", "/agentcore", "/agentcore/", "/agentcore/index.html", "/agentcore/css/app.css"}) {
            assertThat(get(http, context, path).statusCode()).as(path).isEqualTo(404);
        }
    }
}
