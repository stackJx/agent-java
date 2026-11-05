package com.agentcore.domain.conversation.event;

import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.shared.model.DomainEvent;
import java.time.Instant;

/** 会话元数据变更（例如记录所用模型、来源渠道、用户标识）。 */
public record MetadataUpdated(ConversationId conversationId, String key, String value, Instant occurredAt) implements DomainEvent {
}
