package com.agentcore.domain.agent;

import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.model.StopReason;

public record RunResult(String conversationId, String text, StopReason stopReason, int turns, TokenUsage usage, String error) {

    public boolean ok() {
        return stopReason == StopReason.END_TURN && error == null;
    }
}
