package com.agentcore.domain.conversation.model;

import java.time.Instant;

/** 会话列表项（读模型）。 */
public record ConversationSummary(ConversationId id, String title, int messageCount, Instant updatedAt, TokenUsage usage) {
}
