package com.agentcore.domain.conversation.event;

import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.shared.model.DomainEvent;
import java.time.Instant;
import java.util.Map;

public record ConversationStarted(ConversationId conversationId, Map<String, String> metadata, Instant occurredAt)
        implements DomainEvent {
}
