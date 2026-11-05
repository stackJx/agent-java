package com.agentcore.infrastructure.persistence.jdbc;

import com.agentcore.domain.conversation.Conversation;
import com.agentcore.domain.conversation.ConversationId;
import com.agentcore.domain.conversation.ConversationRepository;
import com.agentcore.domain.conversation.ConversationSummary;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.conversation.event.ConversationStarted;
import com.agentcore.domain.shared.ConcurrencyConflictException;
import com.agentcore.domain.shared.DomainEvent;
import com.agentcore.infrastructure.json.DomainCodec;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话仓储（JDBC）：领域事件按序追加写入 {@code <prefix>conversation_event}，会话摘要（标题、消息数、用量、
 * 版本号）写入 {@code <prefix>conversation} 供列表查询。
 *
 * <p>乐观并发：会话行的 {@code last_seq} 是版本号，更新时带 {@code WHERE last_seq = 旧值}；事件表主键
 * {@code (conversation_id, seq)} 兜底。多个实例同时写同一会话时，后写的一方得到 {@link ConcurrencyConflictException}。
 *
 * <p>进程内有身份映射缓存；每次 findById 先比对数据库版本号，被其他实例推进过就重新加载，不会读到旧数据。
 */
public final class JdbcConversationRepository implements ConversationRepository {

    private final JdbcStore store;
    private final String conversations;
    private final String events;
    private final Map<ConversationId, Conversation> identityMap = new ConcurrentHashMap<>();
    private final Map<ConversationId, Long> versions = new ConcurrentHashMap<>();

    public JdbcConversationRepository(JdbcStore store) {
        this.store = store;
        this.conversations = store.table("conversation");
        this.events = store.table("conversation_event");
    }

    public JdbcStore store() {
        return store;
    }

    // ------------------------------------------------------------------ 写

    /**
     * 追加保存新事件（乐观并发控制）。
     *
     * <ol>
     *   <li>取走聚合上的新事件；第一个是 ConversationStarted 说明是新会话，INSERT 摘要行；</li>
     *   <li>否则 {@code UPDATE ... WHERE id = ? AND last_seq = 我上次看到的版本}：更新 0 行说明别的实例先写了，抛冲突；</li>
     *   <li>事件按 seq = 版本+1、+2…… 批量插入。(conversation_id, seq) 是主键，万一两边都通过了第 2 步，
     *       第二个插入也会因唯一约束失败，同样变成冲突；</li>
     *   <li>以上在一个事务里完成。失败时丢弃内存缓存，下次 findById 从数据库重新加载。</li>
     * </ol>
     */
    @Override
    public void save(Conversation c) {
        List<DomainEvent> pending = c.pullEvents();
        if (pending.isEmpty()) {
            identityMap.putIfAbsent(c.id(), c);
            return;
        }
        boolean isNew = pending.get(0) instanceof ConversationStarted;
        try {
            long saved = store.inTransaction(conn -> {
                long base = isNew ? 0 : currentVersion(conn, c.id());
                long last = base + pending.size();
                if (isNew) {
                    insertConversation(conn, c, last);
                } else if (updateConversation(conn, c, base, last) == 0) {
                    throw new ConcurrencyConflictException("conversation " + c.id() + " was modified concurrently (expected version " + base + ")");
                }
                insertEvents(conn, c.id(), base, pending);
                return last;
            });
            versions.put(c.id(), saved);
            identityMap.put(c.id(), c);
        } catch (RuntimeException e) {
            // 内存中的聚合与存储已分叉：丢弃缓存，下次从数据库重新加载
            identityMap.remove(c.id());
            versions.remove(c.id());
            throw e;
        }
    }

    private long currentVersion(Connection conn, ConversationId id) throws SQLException {
        Long known = versions.get(id);
        if (known != null) {
            return known;
        }
        Long db = selectVersion(conn, id);
        if (db == null) {
            throw new ConcurrencyConflictException("conversation " + id + " does not exist in the database");
        }
        return db;
    }

    private void insertConversation(Connection conn, Conversation c, long lastSeq) throws SQLException {
        String sql = "INSERT INTO " + conversations + " (id, title, metadata, message_count, last_seq, input_tokens, output_tokens, "
                + "cache_read_tokens, cache_write_tokens, requests, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            TokenUsage u = c.usage();
            ps.setString(1, c.id().value());
            ps.setString(2, title(c));
            ps.setString(3, Json.write(c.metadata()));
            ps.setInt(4, c.size());
            ps.setLong(5, lastSeq);
            ps.setLong(6, u.inputTokens());
            ps.setLong(7, u.outputTokens());
            ps.setLong(8, u.cacheReadTokens());
            ps.setLong(9, u.cacheWriteTokens());
            ps.setInt(10, u.requests());
            ps.setTimestamp(11, Timestamp.from(c.createdAt()));
            ps.setTimestamp(12, Timestamp.from(c.updatedAt()));
            ps.executeUpdate();
        }
    }

    private int updateConversation(Connection conn, Conversation c, long expected, long lastSeq) throws SQLException {
        String sql = "UPDATE " + conversations + " SET title = ?, metadata = ?, message_count = ?, last_seq = ?, input_tokens = ?, "
                + "output_tokens = ?, cache_read_tokens = ?, cache_write_tokens = ?, requests = ?, updated_at = ? WHERE id = ? AND last_seq = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            TokenUsage u = c.usage();
            ps.setString(1, title(c));
            ps.setString(2, Json.write(c.metadata()));
            ps.setInt(3, c.size());
            ps.setLong(4, lastSeq);
            ps.setLong(5, u.inputTokens());
            ps.setLong(6, u.outputTokens());
            ps.setLong(7, u.cacheReadTokens());
            ps.setLong(8, u.cacheWriteTokens());
            ps.setInt(9, u.requests());
            ps.setTimestamp(10, Timestamp.from(c.updatedAt()));
            ps.setString(11, c.id().value());
            ps.setLong(12, expected);
            return ps.executeUpdate();
        }
    }

    private void insertEvents(Connection conn, ConversationId id, long base, List<DomainEvent> pending) throws SQLException {
        String sql = "INSERT INTO " + events + " (conversation_id, seq, event_type, payload, created_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            long seq = base;
            for (DomainEvent e : pending) {
                JsonNode n = DomainCodec.event(e);
                ps.setString(1, id.value());
                ps.setLong(2, ++seq);
                ps.setString(3, n.path("t").asText());
                ps.setString(4, Json.write(n));
                ps.setTimestamp(5, Timestamp.from(e.occurredAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String title(Conversation c) {
        String t = c.title();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    // ------------------------------------------------------------------ 读

    /** 先查数据库里的版本号：和缓存一致就用缓存；不一致（别的实例写过）或没缓存就读出全部事件重放。 */
    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return store.query(conn -> {
            Long dbVersion = selectVersion(conn, id);
            if (dbVersion == null) {
                identityMap.remove(id);
                versions.remove(id);
                return Optional.empty();
            }
            Conversation cached = identityMap.get(id);
            if (cached != null && dbVersion.equals(versions.get(id))) {
                return Optional.of(cached);
            }
            List<DomainEvent> history = new ArrayList<>();
            long lastSeq = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT seq, payload FROM " + events + " WHERE conversation_id = ? ORDER BY seq")) {
                ps.setString(1, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lastSeq = rs.getLong(1);
                        DomainEvent e = DomainCodec.event(Json.read(rs.getString(2)), id);
                        if (e != null) {
                            history.add(e);
                        }
                    }
                }
            }
            if (history.isEmpty()) {
                return Optional.empty();
            }
            Conversation loaded = Conversation.replay(history);
            identityMap.put(id, loaded);
            versions.put(id, lastSeq);
            return Optional.of(loaded);
        });
    }

    private Long selectVersion(Connection conn, ConversationId id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_seq FROM " + conversations + " WHERE id = ?")) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    @Override
    public Optional<Conversation> findLatest() {
        String id = store.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM " + conversations + " ORDER BY updated_at DESC")) {
                ps.setMaxRows(1);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        return id == null ? Optional.empty() : findById(ConversationId.of(id));
    }

    @Override
    public List<ConversationSummary> list(int limit) {
        return store.query(conn -> {
            List<ConversationSummary> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, title, message_count, updated_at, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_write_tokens, requests FROM " + conversations + " ORDER BY updated_at DESC")) {
                ps.setMaxRows(Math.max(1, limit)); // 标准 JDBC 分页，不依赖 LIMIT / FETCH FIRST 方言
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp ts = rs.getTimestamp(4);
                        out.add(new ConversationSummary(ConversationId.of(rs.getString(1)), nullToEmpty(rs.getString(2)), rs.getInt(3),
                                ts == null ? Instant.EPOCH : ts.toInstant(),
                                new TokenUsage(rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getInt(9))));
                    }
                }
            }
            return out;
        });
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
