package com.agentcore.domain.conversation.event;

import com.agentcore.domain.conversation.ConversationId;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.shared.DomainEvent;
import java.time.Instant;

/** 不对应消息的额外用量（摘要请求、子 Agent 等）。 */
public record UsageRecorded(ConversationId conversationId, TokenUsage usage, String reason, Instant occurredAt)
        implements DomainEvent {
}
