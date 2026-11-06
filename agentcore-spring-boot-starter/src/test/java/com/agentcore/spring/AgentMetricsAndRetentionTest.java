package com.agentcore.spring;

import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.call;
import static com.agentcore.infrastructure.llm.mock.ScriptedModelGateway.reply;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.application.AgentService;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.spring.metrics.AgentCoreMetricsAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AgentMetricsAndRetentionTest {

    @TempDir
    Path dir;

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentCoreAutoConfiguration.class, AgentCoreMetricsAutoConfiguration.class))
                .withPropertyValues(
                        "agentcore.agent.working-directory=" + dir,
                        "agentcore.sessions.dir=" + dir.resolve("sessions"),
                        "agentcore.memory.enabled=false",
                        "agentcore.skills.enabled=false",
                        "agentcore.subagent-max-depth=0");
    }

    /** 模型先调一个只读工具（放行）和一个写工具（没有审批人，被拒绝），然后结束。 */
    @Configuration(proxyBeanMethods = false)
    static class Scripted {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ModelGateway scriptedModel() {
            return new ScriptedModelGateway()
                    .then(reply("看看", call("peek", "name", "a"), call("change", "name", "b")))
                    .then(reply("好了"));
        }

        @Bean
        Tool peek() {
            return FunctionTool.builder("peek").readOnly(true).schema(Schemas.object().string("name", "", true).build())
                    .handler((args, context) -> ToolOutput.text("ok")).build();
        }

        @Bean
        Tool change() {
            return FunctionTool.builder("change").schema(Schemas.object().string("name", "", true).build())
                    .handler((args, context) -> ToolOutput.text("changed")).build();
        }
    }

    @Test
    void runsModelCallsToolsAndApprovalsAreMeasured() {
        runner().withUserConfiguration(Scripted.class).run(context -> {
            AgentService service = context.getBean(AgentService.class);
            String id = service.startConversation(Map.of()).id().value();
            assertThat(service.chat(id, "go", event -> { }).text()).isEqualTo("好了");

            MeterRegistry meters = context.getBean(MeterRegistry.class);
            assertThat(meters.get("agentcore.runs").tag("stop_reason", "END_TURN").timer().count()).isEqualTo(1);
            assertThat(meters.get("agentcore.model.calls").timers()).isNotEmpty();
            assertThat(meters.get("agentcore.model.calls").timers().stream().mapToLong(timer -> timer.count()).sum()).isEqualTo(2);
            assertThat(meters.get("agentcore.tool.calls").tag("tool", "peek").tag("outcome", "success").timer().count()).isEqualTo(1);
            assertThat(meters.get("agentcore.tool.calls").tag("tool", "change").tag("outcome", "error").timer().count()).isEqualTo(1);
            assertThat(meters.get("agentcore.approvals").tag("tool", "change").tag("decision", "deny").timer().count()).isEqualTo(1);
        });
    }

    @Test
    void metricsCanBeTurnedOff() {
        runner().withUserConfiguration(Scripted.class).withPropertyValues("agentcore.metrics.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(com.agentcore.spring.metrics.MicrometerRunObserver.class));
    }

    @Test
    void expiredConversationsAreDeleted() {
        runner().withPropertyValues("agentcore.model=mock", "agentcore.sessions.retention=30d").run(context -> {
            AgentService service = context.getBean(AgentService.class);
            String oldId = service.startConversation(Map.of()).id().value();
            String recentId = service.startConversation(Map.of()).id().value();
            Path oldFile = dir.resolve("sessions").resolve(oldId + ".jsonl");
            Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(Duration.ofDays(40))));

            context.getBean(ConversationRetention.class).deleteExpired();
            assertThat(oldFile).doesNotExist();
            assertThat(service.find(recentId)).isPresent();
        });
    }

    @Test
    void retentionIsOffByDefault() {
        runner().withPropertyValues("agentcore.model=mock")
                .run(context -> assertThat(context).doesNotHaveBean(ConversationRetention.class));
    }
}
