package com.agentcore.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.application.AgentService;
import com.agentcore.domain.conversation.ConversationRepository;
import com.agentcore.domain.memory.MemoryEntry;
import com.agentcore.domain.memory.MemoryRepository;
import com.agentcore.domain.memory.MemoryType;
import com.agentcore.infrastructure.persistence.FileMemoryRepository;
import com.agentcore.infrastructure.persistence.JsonlConversationRepository;
import com.agentcore.infrastructure.persistence.jdbc.JdbcConversationRepository;
import com.agentcore.infrastructure.persistence.jdbc.JdbcMemoryRepository;
import com.agentcore.infrastructure.persistence.jdbc.JdbcPlatform;
import com.agentcore.infrastructure.persistence.jdbc.JdbcStore;
import com.agentcore.spring.jdbc.AgentCoreJdbcAutoConfiguration;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentCoreJdbcAutoConfigurationTest {

    @TempDir
    Path dir;

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, AgentCoreJdbcAutoConfiguration.class,
                        AgentCoreAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:agent_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "agentcore.model=mock",
                        "agentcore.agent.working-directory=" + dir,
                        "agentcore.sessions.dir=" + dir.resolve("sessions"),
                        "agentcore.memory.dir=" + dir.resolve("memory"));
    }

    @Test
    void storesConversationsAndMemoryInTheApplicationDataSource() {
        runner().withPropertyValues("agentcore.persistence.jdbc.enabled=true", "agentcore.persistence.jdbc.platform=mysql",
                        "agentcore.persistence.jdbc.memory-scope=shop")
                .run(ctx -> {
                    assertThat(ctx.getBean(ConversationRepository.class)).isInstanceOf(JdbcConversationRepository.class);
                    assertThat(ctx.getBean(MemoryRepository.class)).isInstanceOf(JdbcMemoryRepository.class);
                    JdbcStore store = ctx.getBean(JdbcStore.class);
                    assertThat(store.platform()).isEqualTo(JdbcPlatform.MYSQL);

                    AgentService svc = ctx.getBean(AgentService.class);
                    String id = svc.startConversation(Map.of("user", "u-1")).id().value();
                    assertThat(svc.chat(id, "你好", e -> { }).ok()).isTrue();
                    ctx.getBean(MemoryRepository.class).save(new MemoryEntry("tone", "回答要简洁", MemoryType.FEEDBACK, "x", LocalDate.now()));

                    int events = store.query(c -> {
                        try (ResultSet rs = c.createStatement().executeQuery(
                                "SELECT COUNT(*) FROM agent_conversation_event WHERE conversation_id = '" + id + "'")) {
                            rs.next();
                            return rs.getInt(1);
                        }
                    });
                    assertThat(events).isEqualTo(3); // started + 用户消息 + 助手回复
                    assertThat(svc.list(10).get(0).title()).isEqualTo("你好");
                    assertThat(svc.find(id).orElseThrow().metadata()).containsEntry("user", "u-1").containsKey("model");
                    assertThat(dir.resolve("sessions")).doesNotExist(); // 没有写文件
                });
    }

    @Test
    void offByDefaultEvenWithDataSource() {
        runner().run(ctx -> { // 有数据源但没开启：仍然存文件，不在业务库里建表
            assertThat(ctx).doesNotHaveBean(JdbcStore.class);
            assertThat(ctx.getBean(ConversationRepository.class)).isInstanceOf(JsonlConversationRepository.class);
            assertThat(ctx.getBean(MemoryRepository.class)).isInstanceOf(FileMemoryRepository.class);
        });
    }

    @Test
    void withoutDataSourceFallsBackToFiles() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentCoreJdbcAutoConfiguration.class, AgentCoreAutoConfiguration.class))
                .withPropertyValues("agentcore.model=mock", "agentcore.persistence.jdbc.enabled=true",
                        "agentcore.sessions.dir=" + dir.resolve("s"), "agentcore.memory.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(ConversationRepository.class)).isInstanceOf(JsonlConversationRepository.class));
    }
}
