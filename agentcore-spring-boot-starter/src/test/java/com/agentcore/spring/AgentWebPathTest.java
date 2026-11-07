package com.agentcore.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.agentcore.spring.web.AgentCoreWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class AgentWebPathTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "/", "///", "relative", "https://example.com/ui", "//example.com/ui", "/ui//nested", "/ui//",
            "/ui/../api", "/ui/./nested", "/%2e%2e", "/ui%2fapi", "/ui?x=1", "/ui#fragment", "/ui;version=1",
            "/ui\\nested", "/ui/*", "/ui/{id}", "/ui/<script>", "/ui\"quoted", "/ui\r\nLocation:evil", "/ui space"})
    void rejectsUnsafeOrAmbiguousPaths(String path) {
        AgentCoreProperties props = new AgentCoreProperties();
        assertThatIllegalArgumentException().isThrownBy(() -> props.getWeb().setBasePath(path))
                .withMessageContaining("agentcore.web.base-path");
        assertThatIllegalArgumentException().isThrownBy(() -> props.getWeb().getUi().setPath(path))
                .withMessageContaining("agentcore.web.ui.path");
    }

    @Test
    void normalizesTrailingSlashesWithoutChangingLiteralSegments() {
        AgentCoreProperties props = new AgentCoreProperties();
        props.getWeb().setBasePath("/services/v1.0-agent_~/");
        props.getWeb().getUi().setPath("/console/chat/");
        assertThat(props.getWeb().getBasePath()).isEqualTo("/services/v1.0-agent_~");
        assertThat(props.getWeb().getUi().getPath()).isEqualTo("/console/chat");
    }

    @Test
    void invalidBoundConfigurationFailsStartup() {
        runner().withPropertyValues("agentcore.web.ui.path=//evil.example/ui")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/agent", "/api/agent/ui", "/api"})
    void uiAndApiNamespacesMustNotOverlap(String ui) {
        runner().withPropertyValues("agentcore.web.ui.path=" + ui).run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "agentcore.web.ui.path and agentcore.web.base-path must not overlap"));
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentCoreAutoConfiguration.class, AgentCoreWebAutoConfiguration.class))
                .withPropertyValues("agentcore.model=mock", "agentcore.sessions.enabled=false",
                        "agentcore.memory.enabled=false", "agentcore.skills.enabled=false",
                        "agentcore.agent.project-instructions=false");
    }
}
