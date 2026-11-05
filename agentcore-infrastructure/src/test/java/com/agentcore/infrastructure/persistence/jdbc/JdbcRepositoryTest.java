package com.agentcore.infrastructure.persistence.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.conversation.Conversation;
import com.agentcore.domain.conversation.ConversationId;
import com.agentcore.domain.conversation.ConversationSummary;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.ProviderPayload;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ThinkingBlock;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.conversation.ToolResult;
import com.agentcore.domain.memory.MemoryEntry;
import com.agentcore.domain.memory.MemoryType;
import com.agentcore.domain.shared.ConcurrencyConflictException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 每个平台的建表脚本在 H2 对应的兼容模式下执行，然后跑同一组仓储行为测试。
 * （真实数据库可以用同样的测试类指向对应的 JDBC URL 验证。）
 */
class JdbcRepositoryTest {

    /** SQLite 是真实数据库（库文件）；其余平台用 H2 的对应兼容模式模拟。 */
    static Stream<Arguments> platforms() {
        return Stream.of(
                Arguments.of(JdbcPlatform.SQLITE, "sqlite"),
                Arguments.of(JdbcPlatform.H2, ""),
                Arguments.of(JdbcPlatform.MYSQL, ";MODE=MySQL;DATABASE_TO_LOWER=TRUE"),
                Arguments.of(JdbcPlatform.POSTGRESQL, ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"),
                Arguments.of(JdbcPlatform.ORACLE, ";MODE=Oracle"),
                Arguments.of(JdbcPlatform.SQLSERVER, ";MODE=MSSQLServer"));
    }

    static SimpleDataSource dataSource(JdbcPlatform platform, String mode) {
        if (platform == JdbcPlatform.SQLITE) {
            try {
                java.nio.file.Path file = java.nio.file.Files.createTempFile("agentcore-", ".db");
                file.toFile().deleteOnExit();
                return new SimpleDataSource("jdbc:sqlite:" + file, null, null);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return new SimpleDataSource("jdbc:h2:mem:" + platform.name().toLowerCase() + "_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1" + mode, "sa", "");
    }

    static JdbcStore store(JdbcPlatform platform, String mode, String prefix) {
        // 平台传 null：按 JDBC 元数据自动识别（SQLite 走真实识别；H2 兼容模式仍报告 H2，所以显式指定）
        JdbcStore store = new JdbcStore(dataSource(platform, mode), platform == JdbcPlatform.SQLITE ? null : platform, prefix);
        assertEquals(platform, store.platform());
        assertEquals(3, JdbcSchema.createIfMissing(store).size());
        assertTrue(JdbcSchema.createIfMissing(store).isEmpty(), "第二次初始化不应重复建表");
        return store;
    }

    /** 一个包含各种内容块（中文、思考签名、工具调用、长文本）的完整会话。 */
    static Conversation sampleConversation() {
        Conversation c = Conversation.start(ConversationId.newId(), Map.of("user", "u-001", "channel", "web"));
        c.addUserInput(List.of(new TextBlock("帮我看看订单 A1001 的物流")));
        c.addAssistantReply(Message.assistant(List.of(
                new ThinkingBlock("用户想查物流", "sig-abc", false, null, "anthropic"),
                new TextBlock("我来查一下。"),
                ToolCall.of("toolu_1", "get_order", Map.of("orderId", "A1001", "detail", true))),
                new ProviderPayload("anthropic", "claude-opus-5-5", "[{\"type\":\"thinking\",\"thinking\":\"用户想查物流\",\"signature\":\"sig-abc\"}]")),
                new TokenUsage(1200, 80, 1000, 0, 1));
        c.answerToolCalls(List.of(ToolResult.text("toolu_1", "get_order", "物流：" + "顺丰 SF1234567890；".repeat(3000), false)), List.of());
        c.addAssistantReply(Message.assistant("已发货，顺丰单号 SF1234567890。"), new TokenUsage(3000, 40, 2500, 0, 1));
        return c;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("platforms")
    void persistsAndReplaysConversations(JdbcPlatform platform, String mode) {
        JdbcStore store = store(platform, mode, "agent_");
        JdbcConversationRepository repo = new JdbcConversationRepository(store);
        Conversation c = Conversation.start(ConversationId.newId(), Map.of("user", "u-001"));
        repo.save(c);
        Conversation full = sampleConversation();
        repo.save(full);
        // 分多次保存，模拟一次运行中的逐步落盘
        full.recordUsage(new TokenUsage(500, 100, 0, 0, 1), "compaction");
        full.compact("摘要：用户查询了 A1001 物流", List.of(Message.user("<conversation_summary>…</conversation_summary>")));
        repo.save(full);

        JdbcConversationRepository fresh = new JdbcConversationRepository(store);
        Conversation loaded = fresh.findById(full.id()).orElseThrow();
        assertEquals(full.messages(), loaded.messages());
        assertEquals(full.usage(), loaded.usage());
        assertEquals(1, loaded.compactions());
        assertEquals("u-001", loaded.metadata().get("user"));

        // 压缩前的完整历史也在事件里（可审计）：回放到压缩前
        assertTrue(fresh.findById(c.id()).isPresent());
        List<ConversationSummary> list = fresh.list(10);
        assertEquals(2, list.size());
        assertEquals(full.id(), list.get(0).id()); // 最近更新的在前
        assertEquals("帮我看看订单 A1001 的物流", list.get(0).title());
        assertEquals(new TokenUsage(4700, 220, 3500, 0, 3), list.get(0).usage());
        assertEquals(1, fresh.list(1).size());
        assertEquals(full.id(), fresh.findLatest().orElseThrow().id());
        assertTrue(fresh.findById(ConversationId.of("missing")).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("platforms")
    void detectsConcurrentWritersAndRefreshesStaleCache(JdbcPlatform platform, String mode) {
        JdbcStore store = store(platform, mode, "agent_");
        JdbcConversationRepository instanceA = new JdbcConversationRepository(store);
        JdbcConversationRepository instanceB = new JdbcConversationRepository(store);
        Conversation c = Conversation.start();
        c.addUserInput(List.of(new TextBlock("你好")));
        instanceA.save(c);

        Conversation inA = instanceA.findById(c.id()).orElseThrow();
        Conversation inB = instanceB.findById(c.id()).orElseThrow();
        assertNotSame(inA, inB);
        inB.addAssistantReply(Message.assistant("B 的回复"), TokenUsage.of(1, 1));
        instanceB.save(inB);

        // A 持有旧版本，再写入时被拒绝，不会把历史写乱
        inA.addAssistantReply(Message.assistant("A 的回复"), TokenUsage.of(1, 1));
        assertThrows(ConcurrencyConflictException.class, () -> instanceA.save(inA));

        // A 重新读取时拿到 B 写入后的最新状态
        Conversation reloaded = instanceA.findById(c.id()).orElseThrow();
        assertEquals("B 的回复", reloaded.messages().get(1).text());
        assertEquals(2, reloaded.size());
        // 同一实例内没有变化时返回同一个聚合实例（身份映射）
        assertSame(reloaded, instanceA.findById(c.id()).orElseThrow());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("platforms")
    void memoryCrudSearchAndScopes(JdbcPlatform platform, String mode) {
        JdbcStore store = store(platform, mode, "ac_");
        JdbcMemoryRepository tenantA = new JdbcMemoryRepository(store, "tenant-a");
        JdbcMemoryRepository tenantB = new JdbcMemoryRepository(store, "tenant-b");
        tenantA.save(new MemoryEntry("User Likes Tea", "用户喜欢喝茶", MemoryType.USER, "乌龙茶，不加糖", LocalDate.of(2026, 11, 5)));
        tenantA.save(new MemoryEntry("user-likes-tea", "用户喜欢喝茶（更新）", MemoryType.USER, "乌龙茶", LocalDate.of(2026, 11, 5)));
        tenantA.save(new MemoryEntry("project-deadline", "项目截止日期", MemoryType.PROJECT, "12 月 31 日上线", null));

        assertEquals(List.of("project-deadline", "user-likes-tea"), tenantA.list().stream().map(MemoryEntry::name).toList());
        MemoryEntry tea = tenantA.find("user-likes-tea").orElseThrow();
        assertEquals("用户喜欢喝茶（更新）", tea.description());
        assertEquals(LocalDate.of(2026, 11, 5), tea.updated());
        assertEquals(1, tenantA.search("乌龙茶", 5).size());
        assertTrue(tenantB.list().isEmpty(), "不同 scope 互相隔离");
        assertTrue(tenantA.delete("user-likes-tea"));
        assertFalse(tenantA.delete("user-likes-tea"));
        assertEquals(1, tenantA.list().size());
    }

    @Test
    void detectsPlatformAndRendersScript() {
        SimpleDataSource ds = new SimpleDataSource("jdbc:h2:mem:detect_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcStore store = JdbcStore.of(ds);
        assertEquals(JdbcPlatform.H2, store.platform());
        assertTrue(JdbcSchema.script(store).contains("CREATE TABLE agent_conversation_event"));
        assertEquals(JdbcPlatform.ORACLE, JdbcPlatform.fromProductName("DM DBMS"));
        assertEquals(JdbcPlatform.POSTGRESQL, JdbcPlatform.fromProductName("KingbaseES"));
        assertEquals(JdbcPlatform.MYSQL, JdbcPlatform.fromProductName("MariaDB"));
        assertEquals(JdbcPlatform.SQLSERVER, JdbcPlatform.fromProductName("Microsoft SQL Server"));
        assertEquals(JdbcPlatform.SQLITE, JdbcPlatform.fromProductName("SQLite"));
        assertEquals(JdbcPlatform.MYSQL, JdbcPlatform.parse("mysql"));
        assertNull(JdbcPlatform.parse("auto"));
        assertThrows(IllegalArgumentException.class, () -> JdbcPlatform.parse("sybase"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcStore(ds, JdbcPlatform.H2, "bad;prefix"));
    }
}
