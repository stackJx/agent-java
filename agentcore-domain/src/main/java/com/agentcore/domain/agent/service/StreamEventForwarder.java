package com.agentcore.domain.agent.service;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import java.util.function.Consumer;

/** 把模型调用过程中的回调（流式增量、重试）转成引擎事件，命令行、SSE 据此实时显示。 */
final class StreamEventForwarder implements ModelStreamListener {

    private final Consumer<AgentEvent> emit;

    StreamEventForwarder(Consumer<AgentEvent> emit) {
        this.emit = emit;
    }

    @Override
    public void onText(String delta) {
        emit.accept(new AgentEvent.TextDelta(delta));
    }

    @Override
    public void onThinking(String delta) {
        emit.accept(new AgentEvent.ThinkingDelta(delta));
    }

    @Override
    public void onToolCallStart(String id, String name) {
        emit.accept(new AgentEvent.ToolCallStreaming(id, name));
    }

    @Override
    public void onRetry(int attempt, long delayMillis, String error) {
        emit.accept(new AgentEvent.Retrying(attempt, delayMillis, error));
    }

    @Override
    public void onMalformedOutput(String error) {
        emit.accept(AgentEvent.Notice.warning("malformed model output, retrying the turn: " + error));
    }
}
