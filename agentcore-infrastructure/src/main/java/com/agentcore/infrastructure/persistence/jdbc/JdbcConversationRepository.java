package com.agentcore.infrastructure.persistence.jdbc;

import com.agentcore.domain.conversation.event.ConversationStarted;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.shared.exception.ConcurrencyConflictException;
import com.agentcore.domain.shared.model.DomainEvent;
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

    private static final int MAX_TITLE_LENGTH = 255;

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
    public void save(Conversation conversation) {
        ConversationId id = conversation.id();
        List<DomainEvent> pending = conversation.pullEvents();
        if (pending.isEmpty()) {
            identityMap.putIfAbsent(id, conversation);
            return;
        }
        boolean isNew = pending.get(0) instanceof ConversationStarted;
        try {
            long saved = store.inTransaction(conn -> {
                long base = isNew ? 0 : currentVersion(conn, id);
                long last = base + pending.size();
                if (isNew) {
                    insertConversation(conn, conversation, last);
                } else if (updateConversation(conn, conversation, base, last) == 0) {
                    throw new ConcurrencyConflictException("conversation " + id + " was modified concurrently (expected version " + base + ")");
                }
                insertEvents(conn, id, base, pending);
                return last;
            });
            versions.put(id, saved);
            identityMap.put(id, conversation);
        } catch (RuntimeException e) {
            // 内存中的聚合与存储已分叉：丢弃缓存，下次从数据库重新加载
            identityMap.remove(id);
            versions.remove(id);
            throw e;
        }
    }

    /** 本进程上次写入后记下的版本；没有（例如重启后）就查数据库。 */
    private long currentVersion(Connection conn, ConversationId id) throws SQLException {
        Long known = versions.get(id);
        if (known != null) {
            return known;
        }
        Long stored = selectVersion(conn, id);
        if (stored == null) {
            throw new ConcurrencyConflictException("conversation " + id + " does not exist in the database");
        }
        return stored;
    }

    private void insertConversation(Connection conn, Conversation conversation, long lastSeq) throws SQLException {
        String sql = "INSERT INTO " + conversations + " (id, title, metadata, message_count, last_seq, input_tokens, output_tokens, "
                + "cache_read_tokens, cache_write_tokens, requests, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversation.id().value());
            int next = bindSummary(ps, 2, conversation, lastSeq);
            ps.setTimestamp(next, Timestamp.from(conversation.createdAt()));
            ps.setTimestamp(next + 1, Timestamp.from(conversation.updatedAt()));
            ps.executeUpdate();
        }
    }

    /** 带版本条件的更新，返回更新的行数：0 表示版本对不上（别的实例先写了）。 */
    private int updateConversation(Connection conn, Conversation conversation, long expectedSeq, long lastSeq) throws SQLException {
        String sql = "UPDATE " + conversations + " SET title = ?, metadata = ?, message_count = ?, last_seq = ?, input_tokens = ?, "
                + "output_tokens = ?, cache_read_tokens = ?, cache_write_tokens = ?, requests = ?, updated_at = ? WHERE id = ? AND last_seq = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int next = bindSummary(ps, 1, conversation, lastSeq);
            ps.setTimestamp(next, Timestamp.from(conversation.updatedAt()));
            ps.setString(next + 1, conversation.id().value());
            ps.setLong(next + 2, expectedSeq);
            return ps.executeUpdate();
        }
    }

    /**
     * 从第 index 个参数开始绑定 INSERT / UPDATE 共有的摘要字段：
     * title, metadata, message_count, last_seq, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, requests。
     * 返回下一个参数的位置。
     */
    private static int bindSummary(PreparedStatement ps, int index, Conversation conversation, long lastSeq) throws SQLException {
        TokenUsage usage = conversation.usage();
        ps.setString(index++, title(conversation));
        ps.setString(index++, Json.write(conversation.metadata()));
        ps.setInt(index++, conversation.size());
        ps.setLong(index++, lastSeq);
        ps.setLong(index++, usage.inputTokens());
        ps.setLong(index++, usage.outputTokens());
        ps.setLong(index++, usage.cacheReadTokens());
        ps.setLong(index++, usage.cacheWriteTokens());
        ps.setInt(index++, usage.requests());
        return index;
    }

    private void insertEvents(Connection conn, ConversationId id, long base, List<DomainEvent> pending) throws SQLException {
        String sql = "INSERT INTO " + events + " (conversation_id, seq, event_type, payload, created_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            long seq = base;
            for (DomainEvent event : pending) {
                JsonNode encoded = DomainCodec.event(event);
                ps.setString(1, id.value());
                ps.setLong(2, ++seq);
                ps.setString(3, encoded.path("t").asText());
                ps.setString(4, Json.write(encoded));
                ps.setTimestamp(5, Timestamp.from(event.occurredAt()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String title(Conversation conversation) {
        String title = conversation.title();
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    // ------------------------------------------------------------------ 读

    /** 先查数据库里的版本号：和缓存一致就用缓存；不一致（别的实例写过）或没缓存就读出全部事件重放。 */
    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return store.query(conn -> {
            Long storedVersion = selectVersion(conn, id);
            if (storedVersion == null) {
                identityMap.remove(id);
                versions.remove(id);
                return Optional.empty();
            }
            Conversation cached = identityMap.get(id);
            if (cached != null && storedVersion.equals(versions.get(id))) {
                return Optional.of(cached);
            }
            History history = loadHistory(conn, id);
            if (history.events().isEmpty()) {
                return Optional.empty();
            }
            Conversation loaded = Conversation.replay(history.events());
            identityMap.put(id, loaded);
            versions.put(id, history.lastSeq());
            return Optional.of(loaded);
        });
    }

    /** 一个会话的全部事件（按顺序）和最后一个事件的序号（即版本号）。 */
    private record History(List<DomainEvent> events, long lastSeq) {
    }

    private History loadHistory(Connection conn, ConversationId id) throws SQLException {
        List<DomainEvent> history = new ArrayList<>();
        long lastSeq = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT seq, payload FROM " + events + " WHERE conversation_id = ? ORDER BY seq")) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lastSeq = rs.getLong(1);
                    DomainEvent event = DomainCodec.event(Json.read(rs.getString(2)), id);
                    if (event != null) { // 不认识的事件类型（新版本写入的）跳过
                        history.add(event);
                    }
                }
            }
        }
        return new History(history, lastSeq);
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
            List<ConversationSummary> summaries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, title, message_count, updated_at, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_write_tokens, requests FROM " + conversations + " ORDER BY updated_at DESC")) {
                ps.setMaxRows(Math.max(1, limit)); // 标准 JDBC 分页，不依赖 LIMIT / FETCH FIRST 方言
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp updatedAt = rs.getTimestamp(4);
                        summaries.add(new ConversationSummary(ConversationId.of(rs.getString(1)), nullToEmpty(rs.getString(2)), rs.getInt(3),
                                updatedAt == null ? Instant.EPOCH : updatedAt.toInstant(),
                                new TokenUsage(rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getInt(9))));
                    }
                }
            }
            return summaries;
        });
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
