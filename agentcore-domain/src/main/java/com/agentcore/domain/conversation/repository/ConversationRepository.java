package com.agentcore.domain.conversation.repository;

import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.ConversationSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 会话仓储（端口）。实现应把聚合产生的领域事件追加写入存储。 */
public interface ConversationRepository {

    /** 持久化聚合上尚未保存的领域事件。 */
    void save(Conversation conversation);

    Optional<Conversation> findById(ConversationId id);

    Optional<Conversation> findLatest();

    /** 最近更新的 limit 个会话。 */
    default List<ConversationSummary> list(int limit) {
        return list(limit, null);
    }

    /** 某个用户最近更新的 limit 个会话（元数据 {@link Conversation#OWNER} 等于 owner）；owner 为 null 时不过滤。 */
    List<ConversationSummary> list(int limit, String owner);

    /** 删除一个会话的全部数据（事件和摘要）。返回是否真的删了（会话不存在时返回 false）。 */
    boolean delete(ConversationId id);

    /**
     * 删除在 cutoff 之前就不再更新的会话（保留期限到了），keep 里的会话除外（例如正在运行的）。
     *
     * @return 删除了几个会话
     */
    int deleteNotUpdatedSince(Instant cutoff, Set<ConversationId> keep);
}
