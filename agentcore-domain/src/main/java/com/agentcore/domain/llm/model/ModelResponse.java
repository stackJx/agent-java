package com.agentcore.domain.llm.model;

import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.model.TokenUsage;

/**
 * 模型响应。
 *
 * @param stopDetails 拒答等情况下的厂商细节（JSON 文本），可为 null
 */
public record ModelResponse(Message message, StopReason stopReason, TokenUsage usage, String model,
                            String rawStopReason, String stopDetails) {

    public ModelResponse {
        usage = usage == null ? TokenUsage.ZERO : usage;
    }
}
