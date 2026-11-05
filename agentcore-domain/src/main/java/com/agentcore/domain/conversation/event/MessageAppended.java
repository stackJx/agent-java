package com.agentcore.domain.conversation.event;

import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.shared.model.DomainEvent;
import java.time.Instant;

/** 追加了一条消息。usage 只在助手消息上有值。 */
public record MessageAppended(ConversationId conversationId, Message message, TokenUsage usage, Instant occurredAt)
        implements DomainEvent {
}
