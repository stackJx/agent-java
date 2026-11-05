package com.agentcore.domain.conversation.event;

import com.agentcore.domain.conversation.ConversationId;
import com.agentcore.domain.shared.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record ConversationStarted(ConversationId conversationId, Map<String, String> metadata, Instant occurredAt)
        implements DomainEvent {
}
