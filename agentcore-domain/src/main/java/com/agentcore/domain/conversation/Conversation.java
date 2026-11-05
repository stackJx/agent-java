package com.agentcore.domain.conversation;

import com.agentcore.domain.conversation.event.ConversationCompacted;
import com.agentcore.domain.conversation.event.ConversationStarted;
import com.agentcore.domain.conversation.event.MessageAppended;
import com.agentcore.domain.conversation.event.MetadataUpdated;
import com.agentcore.domain.conversation.event.UsageRecorded;
import com.agentcore.domain.shared.AggregateRoot;
import com.agentcore.domain.shared.DomainEvent;
import com.agentcore.domain.shared.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 会话聚合根。
 *
 * <p>不变式：
 * <ol>
 *   <li>历史只追加；唯一的整体替换是 {@link #compact 压缩}。</li>
 *   <li>助手发起的工具调用必须由紧随其后的一条用户消息<b>逐一</b>给出结果（id 一一对应），
 *       在此之前不能追加新的用户输入或助手回复 —— 这保证历史永远是各厂商 API 都接受的合法序列。</li>
 * </ol>
 * 所有状态变化都会产生领域事件，仓储据此做追加式持久化（事件即存储格式）。
 */
public final class Conversation extends AggregateRoot<ConversationId> {

    /** 最近一次模型请求时的上下文规模，用于精确估算下一次请求的 token 数。 */
    public record ContextSnapshot(int messageCount, long tokens) {
    }

    private final ConversationId id;
    private final Instant createdAt;
    private final Map<String, String> metadata;
    private final List<Message> messages = new ArrayList<>();
    private TokenUsage usage = TokenUsage.ZERO;
    private int compactions;
    private ContextSnapshot snapshot;
    private Instant updatedAt;
    /** 标题：第一条用户输入的首行，确定后不再变化（压缩也不影响）。 */
    private String title;

    private Conversation(ConversationId id, Map<String, String> metadata, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.metadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public static Conversation start(ConversationId id, Map<String, String> metadata) {
        Conversation c = new Conversation(id, metadata, Instant.now());
        c.raise(new ConversationStarted(id, Map.copyOf(c.metadata), c.createdAt));
        return c;
    }

    public static Conversation start() {
        return start(ConversationId.newId(), Map.of());
    }

    /** 由历史事件重建（仓储使用），不会产生新事件。 */
    public static Conversation replay(List<? extends DomainEvent> history) {
        if (history.isEmpty() || !(history.get(0) instanceof ConversationStarted started)) {
            throw new DomainException("event stream must begin with ConversationStarted");
        }
        Conversation c = new Conversation(started.conversationId(), started.metadata(), started.occurredAt());
        for (DomainEvent e : history.subList(1, history.size())) {
            c.apply(e);
        }
        return c;
    }

    private void apply(DomainEvent e) {
        switch (e) {
            case MessageAppended m -> {
                messages.add(m.message());
                if (title == null && m.message().isPlainUserInput() && !m.message().text().isBlank()) {
                    String first = m.message().text().strip().lines().findFirst().orElse("");
                    title = first.length() > 80 ? first.substring(0, 80) : first;
                }
                if (m.usage() != null) {
                    usage = usage.plus(m.usage());
                    if (!m.usage().isEmpty()) {
                        snapshot = new ContextSnapshot(messages.size(), m.usage().totalInput() + m.usage().outputTokens());
                    }
                }
            }
            case ConversationCompacted cc -> {
                messages.clear();
                messages.addAll(cc.messages());
                compactions++;
                snapshot = null;
            }
            case UsageRecorded u -> usage = usage.plus(u.usage());
            case MetadataUpdated mu -> metadata.put(mu.key(), mu.value());
            default -> { }
        }
        updatedAt = e.occurredAt();
    }

    private void record(DomainEvent e) {
        apply(e);
        raise(e);
    }

    // ------------------------------------------------------------------ 行为

    /** 追加用户输入。存在未回复的工具调用时拒绝（先调用 {@link #repairDanglingToolCalls}）。 */
    public synchronized void addUserInput(List<? extends ContentBlock> content) {
        if (content == null || content.isEmpty()) {
            throw new DomainException("user input must not be empty");
        }
        if (content.stream().anyMatch(b -> b instanceof ToolResult || b instanceof ToolCall || b instanceof ThinkingBlock)) {
            throw new DomainException("user input may only contain text and images");
        }
        requireNoPendingToolCalls();
        record(new MessageAppended(id, Message.user(content), null, Instant.now()));
    }

    /** 追加助手回复及其用量。 */
    public synchronized void addAssistantReply(Message message, TokenUsage replyUsage) {
        if (message.role() != Role.ASSISTANT) {
            throw new DomainException("assistant reply must have role ASSISTANT");
        }
        requireNoPendingToolCalls();
        record(new MessageAppended(id, message, replyUsage == null ? TokenUsage.ZERO : replyUsage, Instant.now()));
    }

    /**
     * 回复待处理的工具调用。results 必须与待处理调用一一对应（顺序无关，按调用顺序存储）；
     * extra 是同一轮插入的额外内容（例如运行中用户的补充输入）。
     */
    public synchronized void answerToolCalls(List<ToolResult> results, List<? extends ContentBlock> extra) {
        List<ToolCall> pending = pendingToolCalls();
        if (pending.isEmpty()) {
            throw new DomainException("there are no pending tool calls to answer");
        }
        Map<String, ToolResult> byId = results.stream()
                .collect(Collectors.toMap(ToolResult::toolCallId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<String> expected = pending.stream().map(ToolCall::id).collect(Collectors.toSet());
        if (!byId.keySet().equals(expected)) {
            throw new DomainException("tool results " + byId.keySet() + " do not match pending tool calls " + expected);
        }
        List<ContentBlock> content = new ArrayList<>();
        pending.forEach(c -> content.add(byId.get(c.id())));
        if (extra != null) {
            for (ContentBlock b : extra) {
                if (!(b instanceof TextBlock) && !(b instanceof ImageBlock)) {
                    throw new DomainException("extra content may only contain text and images");
                }
                content.add(b);
            }
        }
        record(new MessageAppended(id, Message.user(content), null, Instant.now()));
    }

    /** 若最后一条助手消息的工具调用没有结果（进程崩溃/取消），补上错误结果使历史重新合法。 */
    public synchronized boolean repairDanglingToolCalls(String reason) {
        List<ToolCall> pending = pendingToolCalls();
        if (pending.isEmpty()) {
            return false;
        }
        List<ToolResult> results = pending.stream().map(c -> ToolResult.text(c.id(), c.name(), reason, true)).toList();
        answerToolCalls(results, List.of());
        return true;
    }

    /** 用摘要后的新历史替换旧历史。新历史必须以用户消息开头且不能留有未回复的工具调用。 */
    public synchronized void compact(String summary, List<Message> newHistory) {
        if (newHistory.isEmpty() || newHistory.get(0).role() != Role.USER) {
            throw new DomainException("compacted history must start with a user message");
        }
        Message last = newHistory.get(newHistory.size() - 1);
        if (last.role() == Role.ASSISTANT && !last.toolCalls().isEmpty()) {
            throw new DomainException("compacted history must not end with unanswered tool calls");
        }
        record(new ConversationCompacted(id, summary, List.copyOf(newHistory), Instant.now()));
    }

    public synchronized void recordUsage(TokenUsage extra, String reason) {
        if (extra != null && !extra.isEmpty()) {
            record(new UsageRecorded(id, extra, reason, Instant.now()));
        }
    }

    public synchronized void putMetadata(String key, String value) {
        if (key == null || value == null || value.equals(metadata.get(key))) {
            return;
        }
        record(new MetadataUpdated(id, key, value, Instant.now()));
    }

    // ------------------------------------------------------------------ 查询

    public synchronized List<ToolCall> pendingToolCalls() {
        if (messages.isEmpty()) {
            return List.of();
        }
        Message last = messages.get(messages.size() - 1);
        return last.role() == Role.ASSISTANT ? last.toolCalls() : List.of();
    }

    private void requireNoPendingToolCalls() {
        if (!pendingToolCalls().isEmpty()) {
            throw new DomainException("the last assistant message has unanswered tool calls");
        }
    }

    @Override
    public ConversationId id() {
        return id;
    }

    public synchronized List<Message> messages() {
        return List.copyOf(messages);
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized TokenUsage usage() {
        return usage;
    }

    public synchronized int compactions() {
        return compactions;
    }

    public synchronized ContextSnapshot contextSnapshot() {
        return snapshot;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized Instant updatedAt() {
        return updatedAt;
    }

    public synchronized Map<String, String> metadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** 标题：第一条用户输入的首行（压缩后保持不变）；还没有输入时取元数据里的 title。 */
    public synchronized String title() {
        return title != null ? title : metadata.getOrDefault("title", "");
    }
}
