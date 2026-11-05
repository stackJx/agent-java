package com.agentcore.domain.agent.model;

import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.model.StopReason;

public record RunResult(String conversationId, String text, StopReason stopReason, int turns, TokenUsage usage, String error) {

    public boolean ok() {
        return stopReason == StopReason.END_TURN && error == null;
    }
}
