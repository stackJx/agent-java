package com.agentcore.domain.conversation;

import java.util.List;
import java.util.Optional;

/** 会话仓储（端口）。实现应把聚合产生的领域事件追加写入存储。 */
public interface ConversationRepository {

    /** 持久化聚合上尚未保存的领域事件。 */
    void save(Conversation conversation);

    Optional<Conversation> findById(ConversationId id);

    Optional<Conversation> findLatest();

    List<ConversationSummary> list(int limit);
}
