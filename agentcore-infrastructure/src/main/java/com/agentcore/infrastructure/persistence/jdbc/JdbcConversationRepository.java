package com.agentcore.infrastructure.persistence.jdbc;

import com.agentcore.domain.conversation.event.ConversationStarted;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.shared.exception.ConcurrencyConflictException;
import com.agentcore.domain.shared.model.DomainEvent;
import com.agentcore.domain.shared.util.BoundedLruCache;
import com.agentcore.infrastructure.json.DomainCodec;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/**
 * 会话仓储（JDBC）：领域事件按序追加写入 {@code <prefix>conversation_event}，会话摘要（标题、消息数、用量、
 * 版本号）写入 {@code <prefix>conversation} 供列表查询。
 *
 * <p>乐观并发：会话行的 {@code last_seq} 是版本号，更新时带 {@code WHERE last_seq = 旧值}；事件表主键
 * {@code (conversation_id, seq)} 兜底。多个实例同时写同一会话时，后写的一方得到 {@link ConcurrencyConflictException}。
 *
 * <p>进程内有身份映射缓存（最多 {@value #MAX_CACHED_CONVERSATIONS} 个会话）；每次 findById 先比对数据库版本号，
 * 被其他实例推进过就重新加载，不会读到旧数据。
 *
 * <p>版本号按<b>聚合实例</b>记录（而不是按会话 ID）：一个实例保存失败后，它记着的仍是自己最后一次成功保存的版本。
 * 只是数据库抖动的话，下次保存版本对得上，连同上次没写进去的事件一起补写；真的被别的实例改过，版本对不上，继续报冲突，
 * 不会把两段分叉的历史拼到一起。
 */
public final class JdbcConversationRepository implements ConversationRepository {

    private static final int MAX_TITLE_LENGTH = 255;
    static final int MAX_CACHED_CONVERSATIONS = 200;

    private final JdbcStore store;
    private final String conversations;
    private final String events;
    private final BoundedLruCache<ConversationId, Conversation> identityMap = new BoundedLruCache<>(MAX_CACHED_CONVERSATIONS);
    /** 聚合实例 → 它最后一次成功保存（或加载）时的版本号。弱引用：实例不再被使用时自动清掉。 */
    private final Map<Conversation, Long> savedVersions = Collections.synchronizedMap(new WeakHashMap<>());

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
     *   <li>看一眼聚合上还没保存的事件；第一个是 ConversationStarted 说明是新会话，INSERT 摘要行；</li>
     *   <li>否则 {@code UPDATE ... WHERE id = ? AND last_seq = 这个实例上次保存的版本}：更新 0 行说明别的实例先写了，抛冲突；</li>
     *   <li>事件按 seq = 版本+1、+2…… 批量插入。(conversation_id, seq) 是主键，万一两边都通过了第 2 步，
     *       第二个插入也会因唯一约束失败，同样变成冲突；</li>
     *   <li>以上在一个事务里完成。成功后才把事件标记为已保存；失败时事件留在聚合里，并丢弃缓存，
     *       其他调用方下次 findById 从数据库重新加载。</li>
     * </ol>
     */
    @Override
    public void save(Conversation conversation) {
        ConversationId id = conversation.id();
        List<DomainEvent> pending = conversation.pendingEvents();
        if (pending.isEmpty()) {
            identityMap.putIfAbsent(id, conversation);
            return;
        }
        boolean isNew = pending.get(0) instanceof ConversationStarted;
        try {
            long saved = store.inTransaction(() -> {
                long base = isNew ? 0 : currentVersion(conversation);
                long last = base + pending.size();
                if (isNew) {
                    insertConversation(conversation, last);
                } else if (updateConversation(conversation, base, last) == 0) {
                    throw new ConcurrencyConflictException("conversation " + id + " was modified concurrently (expected version " + base + ")");
                }
                insertEvents(id, base, pending);
                return last;
            });
            conversation.markEventsSaved(pending);
            savedVersions.put(conversation, saved);
            identityMap.put(id, conversation);
        } catch (RuntimeException e) {
            // 这个实例和数据库可能已经分叉：别的调用方不要再用缓存里的它
            identityMap.remove(id);
            throw e;
        }
    }

    /** 这个实例上次成功保存（或加载）时的版本；没有记录就查数据库。 */
    private long currentVersion(Conversation conversation) {
        Long known = savedVersions.get(conversation);
        if (known != null) {
            return known;
        }
        return selectVersion(conversation.id()).orElseThrow(
                () -> new ConcurrencyConflictException("conversation " + conversation.id() + " does not exist in the database"));
    }

    private void insertConversation(Conversation conversation, long lastSeq) {
        TokenUsage usage = conversation.usage();
        store.jdbc().sql("INSERT INTO " + conversations + " (id, title, metadata, message_count, last_seq, input_tokens, output_tokens, "
                        + "cache_read_tokens, cache_write_tokens, requests, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(conversation.id().value(), title(conversation), Json.write(conversation.metadata()), conversation.size(), lastSeq,
                        usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens(), usage.requests(),
                        Timestamp.from(conversation.createdAt()), Timestamp.from(conversation.updatedAt()))
                .update();
    }

    /** 带版本条件的更新，返回更新的行数：0 表示版本对不上（别的实例先写了）。 */
    private int updateConversation(Conversation conversation, long expectedSeq, long lastSeq) {
        TokenUsage usage = conversation.usage();
        return store.jdbc().sql("UPDATE " + conversations + " SET title = ?, metadata = ?, message_count = ?, last_seq = ?, "
                        + "input_tokens = ?, output_tokens = ?, cache_read_tokens = ?, cache_write_tokens = ?, requests = ?, updated_at = ? "
                        + "WHERE id = ? AND last_seq = ?")
                .params(title(conversation), Json.write(conversation.metadata()), conversation.size(), lastSeq,
                        usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens(), usage.requests(),
                        Timestamp.from(conversation.updatedAt()), conversation.id().value(), expectedSeq)
                .update();
    }

    private void insertEvents(ConversationId id, long base, List<DomainEvent> pending) {
        List<Object[]> rows = new ArrayList<>();
        long seq = base;
        for (DomainEvent event : pending) {
            JsonNode encoded = DomainCodec.event(event);
            rows.add(new Object[] {id.value(), ++seq, encoded.path("t").asText(), Json.write(encoded), Timestamp.from(event.occurredAt())});
        }
        store.batchUpdate("INSERT INTO " + events + " (conversation_id, seq, event_type, payload, created_at) VALUES (?, ?, ?, ?, ?)", rows);
    }

    private static String title(Conversation conversation) {
        String title = conversation.title();
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    // ------------------------------------------------------------------ 读

    /** 先查数据库里的版本号：和缓存一致就用缓存；不一致（别的实例写过）或没缓存就读出全部事件重放。 */
    @Override
    public Optional<Conversation> findById(ConversationId id) {
        Optional<Long> storedVersion = selectVersion(id);
        if (storedVersion.isEmpty()) {
            identityMap.remove(id);
            return Optional.empty();
        }
        Conversation cached = identityMap.get(id);
        if (cached != null && storedVersion.get().equals(savedVersions.get(cached))) {
            return Optional.of(cached);
        }
        History history = loadHistory(id);
        if (history.events().isEmpty()) {
            return Optional.empty();
        }
        Conversation loaded = Conversation.replay(history.events());
        savedVersions.put(loaded, history.lastSeq());
        identityMap.put(id, loaded);
        return Optional.of(loaded);
    }

    /** 一个会话的全部事件（按顺序）和最后一个事件的序号（即版本号）。 */
    private record History(List<DomainEvent> events, long lastSeq) {
    }

    /** 事件表里的一行：序号 + JSON。 */
    private record StoredEvent(long seq, String payload) {
    }

    private History loadHistory(ConversationId id) {
        List<StoredEvent> rows = store.jdbc().sql("SELECT seq, payload FROM " + events + " WHERE conversation_id = ? ORDER BY seq")
                .param(id.value())
                .query((row, rowNumber) -> new StoredEvent(row.getLong(1), row.getString(2)))
                .list();
        List<DomainEvent> history = new ArrayList<>();
        for (StoredEvent row : rows) {
            DomainEvent event = DomainCodec.event(Json.read(row.payload()), id);
            if (event != null) { // 不认识的事件类型（新版本写入的）跳过
                history.add(event);
            }
        }
        long lastSeq = rows.isEmpty() ? 0 : rows.getLast().seq();
        return new History(history, lastSeq);
    }

    private Optional<Long> selectVersion(ConversationId id) {
        return store.jdbc().sql("SELECT last_seq FROM " + conversations + " WHERE id = ?")
                .param(id.value())
                .query(Long.class)
                .optional();
    }

    @Override
    public Optional<Conversation> findLatest() {
        Optional<String> id = store.jdbc().sql("SELECT id FROM " + conversations + " ORDER BY updated_at DESC")
                .withMaxRows(1)
                .query(String.class)
                .optional();
        return id.flatMap(value -> findById(ConversationId.of(value)));
    }

    /** 摘要表里的一行：摘要本身，加上从元数据里取出的 owner（按用户过滤时用）。 */
    private record SummaryRow(ConversationSummary summary, String owner) {
    }

    private static final String SUMMARY_COLUMNS =
            "id, title, message_count, updated_at, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, requests, metadata";
    /** 按用户过滤时每次从数据库取多少行（边取边过滤，凑够 limit 个就停）。 */
    private static final int OWNER_SCAN_FETCH_SIZE = 200;

    /**
     * 不按用户过滤时，直接取最近的 limit 行（标准 JDBC 分页，不依赖 LIMIT / FETCH FIRST 方言）；
     * 按用户过滤时，从最近的开始逐行读、过滤，凑够 limit 个就停 —— owner 在元数据 JSON 里，各数据库的 JSON 查询写法不通用。
     */
    @Override
    public List<ConversationSummary> list(int limit, String owner) {
        String sql = "SELECT " + SUMMARY_COLUMNS + " FROM " + conversations + " ORDER BY updated_at DESC";
        if (owner == null) {
            return store.jdbc().sql(sql)
                    .withMaxRows(Math.max(1, limit))
                    .query(JdbcConversationRepository::summaryRow)
                    .list().stream().map(SummaryRow::summary).toList();
        }
        try (Stream<SummaryRow> rows = store.jdbc().sql(sql)
                .withFetchSize(OWNER_SCAN_FETCH_SIZE)
                .query(JdbcConversationRepository::summaryRow)
                .stream()) {
            return rows.filter(row -> owner.equals(row.owner())).limit(limit).map(SummaryRow::summary).toList();
        }
    }

    private static SummaryRow summaryRow(ResultSet row, int rowNumber) throws SQLException {
        Timestamp updatedAt = row.getTimestamp(4);
        ConversationSummary summary = new ConversationSummary(ConversationId.of(row.getString(1)), nullToEmpty(row.getString(2)),
                row.getInt(3), updatedAt == null ? Instant.EPOCH : updatedAt.toInstant(),
                new TokenUsage(row.getLong(5), row.getLong(6), row.getLong(7), row.getLong(8), row.getInt(9)));
        return new SummaryRow(summary, ownerOf(row.getString(10)));
    }

    private static String ownerOf(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        return Json.read(metadataJson).path(Conversation.OWNER).asText(null);
    }

    // ------------------------------------------------------------------ 删除

    /** 删除事件和摘要（同一个事务），并丢掉缓存。 */
    @Override
    public boolean delete(ConversationId id) {
        boolean deleted = store.inTransaction(() -> {
            store.jdbc().sql("DELETE FROM " + events + " WHERE conversation_id = ?").param(id.value()).update();
            return store.jdbc().sql("DELETE FROM " + conversations + " WHERE id = ?").param(id.value()).update() > 0;
        });
        identityMap.remove(id);
        return deleted;
    }

    /** 先查出过期的会话 ID，再逐个删除（每个会话一个事务，清理大量数据时也不会锁表太久）。 */
    @Override
    public int deleteNotUpdatedSince(Instant cutoff, Set<ConversationId> keep) {
        List<ConversationId> expired = store.jdbc().sql("SELECT id FROM " + conversations + " WHERE updated_at < ?")
                .param(Timestamp.from(cutoff))
                .query(String.class)
                .list().stream()
                .map(ConversationId::of)
                .filter(id -> !keep.contains(id))
                .toList();
        int deleted = 0;
        for (ConversationId id : expired) {
            if (delete(id)) {
                deleted++;
            }
        }
        return deleted;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
