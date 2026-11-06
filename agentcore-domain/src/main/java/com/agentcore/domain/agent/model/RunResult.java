package com.agentcore.domain.agent.model;

import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.model.StopReason;
import java.util.List;

/**
 * 一次运行的结果。
 *
 * @param unprocessedSteering 运行结束时还没来得及交给模型的插话（例如运行被取消时用户刚好又说了一句）；
 *                            调用方可以把它们作为下一条消息发送
 */
public record RunResult(String conversationId, String text, StopReason stopReason, int turns, TokenUsage usage, String error,
                        List<String> unprocessedSteering) {

    public RunResult {
        unprocessedSteering = unprocessedSteering == null ? List.of() : List.copyOf(unprocessedSteering);
    }

    public RunResult(String conversationId, String text, StopReason stopReason, int turns, TokenUsage usage, String error) {
        this(conversationId, text, stopReason, turns, usage, error, List.of());
    }

    public boolean ok() {
        return stopReason == StopReason.END_TURN && error == null;
    }
}
