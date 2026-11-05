package com.agentcore.domain.conversation.event;

import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.shared.model.DomainEvent;
import java.time.Instant;
import java.util.List;

/** 上下文被压缩：历史替换为摘要 + 保留的末尾消息。 */
public record ConversationCompacted(ConversationId conversationId, String summary, List<Message> messages, Instant occurredAt)
        implements DomainEvent {
}
