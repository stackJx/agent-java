package com.agentcore.infrastructure.persistence;

import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存仓储（测试 / 不需要持久化的嵌入场景）。 */
public final class InMemoryConversationRepository implements ConversationRepository {

    private final Map<ConversationId, Conversation> store = new ConcurrentHashMap<>();

    @Override
    public void save(Conversation c) {
        c.pullEvents();
        store.put(c.id(), c);
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Conversation> findLatest() {
        return store.values().stream().max(Comparator.comparing(Conversation::updatedAt));
    }

    @Override
    public List<ConversationSummary> list(int limit) {
        return store.values().stream().sorted(Comparator.comparing(Conversation::updatedAt).reversed()).limit(limit)
                .map(c -> new ConversationSummary(c.id(), c.title(), c.size(), c.updatedAt(), c.usage())).toList();
    }
}
