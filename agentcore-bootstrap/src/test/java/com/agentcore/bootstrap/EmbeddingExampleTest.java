package com.agentcore.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.agent.RunResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 嵌入示例（不用 Spring）始终能跑通；独立部署版（Spring Boot 命令行）的各个命令和配置文件都能用。 */
class EmbeddingExampleTest {

    @TempDir
    Path dir;

    /** 测试不碰本机的 ~/.agentcore：记忆、技能关掉，会话写到临时目录。 */
    String[] cli(String... args) {
        List<String> out = new ArrayList<>(List.of(args));
        out.addAll(List.of("--cwd", dir.toString(), "--agentcore.memory.enabled=false", "--agentcore.skills.enabled=false",
                "--agentcore.sessions.dir=" + dir.resolve("sessions")));
        return out.toArray(String[]::new);
    }

    @Test
    void exampleRunsOffline() {
        RunResult r = EmbeddingExample.run(dir, false);
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertTrue(r.text().contains("已转人工"));
        assertEquals(3, r.turns());
    }

    @Test
    void cliRunsWithMockModel() {
        assertEquals(0, AgentCoreApplication.run(cli("run", "-m", "mock", "--no-session", "你好")));
        assertEquals(2, AgentCoreApplication.run(cli("run", "-m", "mock", "--resume", "no-such-session", "hi"))); // 会话不存在
        assertEquals(2, AgentCoreApplication.run(cli("run", "-m", "not-a-model-spec", "hi")));                    // 启动失败：模型写法不对
    }

    @Test
    void configFilesUseSpringFormatAndLaterOnesWin() throws Exception {
        Files.createDirectories(dir.resolve(".agentcore"));
        Files.writeString(dir.resolve(".agentcore/config.yaml"), "agentcore:\n  model: broken-spec\n");
        Files.writeString(dir.resolve("agentcore.yaml"), "agentcore:\n  model: mock\n");   // 覆盖 .agentcore/config.yaml
        assertEquals(0, AgentCoreApplication.run(cli("run", "--no-session", "hi")));

        Files.writeString(dir.resolve("agentcore.yaml"), "agentcore:\n  model: broken-spec\n");
        Path extra = dir.resolve("extra.yaml");
        Files.writeString(extra, "agentcore:\n  model: mock\n");                            // --config 覆盖 agentcore.yaml
        assertEquals(0, AgentCoreApplication.run(cli("run", "--config", extra.toString(), "--no-session", "hi")));
        assertEquals(2, AgentCoreApplication.run(cli("run", "--config", dir.resolve("missing.yaml").toString(), "hi")));
    }

    @Test
    void initTemplateBindsCleanly() {
        assertEquals(0, AgentCoreApplication.run(cli("init")));
        assertTrue(Files.exists(dir.resolve("agentcore.yaml")));
        assertEquals(1, AgentCoreApplication.run(cli("init")));                                // 已存在
        // 模板里的每一项都能绑定到 AgentCoreProperties（换成离线模型跑一次）
        assertEquals(0, AgentCoreApplication.run(cli("run", "-m", "mock", "--no-session", "hi")));
    }

    @Test
    void databaseStorageThroughSpringDataSource() throws Exception {
        String url = "jdbc:h2:mem:cli_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        String[] db = {"--spring.datasource.url=" + url, "--agentcore.persistence.jdbc.enabled=true"};
        List<String> args = new ArrayList<>(List.of("run", "-m", "mock"));
        args.addAll(List.of(db));
        args.add("你好");
        assertEquals(0, AgentCoreApplication.run(cli(args.toArray(String[]::new))));

        try (Connection c = DriverManager.getConnection(url, "sa", ""); ResultSet rs = c.createStatement()
                .executeQuery("SELECT title, message_count FROM agent_conversation")) {
            assertTrue(rs.next());
            assertEquals("你好", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertFalse(rs.next());
        }
        assertFalse(Files.exists(dir.resolve("sessions")), "存进数据库时不写会话文件");
        assertEquals(0, AgentCoreApplication.run(cli("sessions", db[0], db[1])));
    }

    @Test
    void sessionsCommandDoesNotNeedAModel() {
        // 懒加载启动：只建仓储，不创建模型（没有配置模型也能列出会话）
        assertEquals(0, AgentCoreApplication.run(cli("sessions", "--agentcore.model=")));
    }
}
