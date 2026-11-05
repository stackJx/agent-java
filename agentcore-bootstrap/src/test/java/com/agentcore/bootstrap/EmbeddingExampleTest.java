package com.agentcore.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.agent.RunResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保证示例代码始终能编译、能跑通。 */
class EmbeddingExampleTest {

    @TempDir
    Path dir;

    @Test
    void exampleRunsOffline() {
        RunResult r = EmbeddingExample.run(dir, false);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertTrue(r.text().contains("已转人工"));
        assertEquals(3, r.turns());
    }

    @Test
    void standaloneRuntimeCanStoreInDatabase() {
        com.agentcore.infrastructure.config.Settings s = com.agentcore.infrastructure.config.Settings.load(null, dir);
        s.set("model", "mock");
        s.set("persistence.jdbc.url", "jdbc:h2:mem:standalone_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (com.agentcore.application.AgentRuntime rt = RuntimeAssembler.assemble(s, RuntimeAssembler.Options.of(dir))) {
            assertInstanceOf(com.agentcore.infrastructure.persistence.jdbc.JdbcConversationRepository.class, rt.service().repository());
            assertInstanceOf(com.agentcore.infrastructure.persistence.jdbc.JdbcMemoryRepository.class, rt.memory());
            String id = rt.service().startConversation(java.util.Map.of()).id().value();
            assertTrue(rt.service().chat(id, "你好", e -> { }).ok());
            assertEquals("你好", rt.service().list(5).get(0).title());
        }
    }

    @Test
    void cliRunsWithMockModel() {
        assertEquals(0, AgentCoreApplication.run(new String[]{"run", "-m", "mock", "--no-session", "--cwd", dir.toString(), "你好"}));
        assertEquals(2, AgentCoreApplication.run(new String[]{"run", "-m", "mock", "--cwd", dir.toString(),
                "--resume", "no-such-session", "hi"})); // 会话不存在 → 参数错误
    }
}
