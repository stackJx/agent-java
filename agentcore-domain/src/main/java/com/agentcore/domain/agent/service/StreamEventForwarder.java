package com.agentcore.domain.agent.service;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import java.util.function.Consumer;

/** 把模型调用过程中的回调（流式增量、重试）转成引擎事件，命令行、SSE 据此实时显示。 */
final class StreamEventForwarder implements ModelStreamListener {

    private final Consumer<AgentEvent> emit;
    /** false：只转发重试通知，不转发文字增量（生成压缩摘要时用，摘要不是给用户看的回复）。 */
    private final boolean forwardContent;

    private StreamEventForwarder(Consumer<AgentEvent> emit, boolean forwardContent) {
        this.emit = emit;
        this.forwardContent = forwardContent;
    }

    /** 模型回复：增量、重试都转发。 */
    static StreamEventForwarder forReply(Consumer<AgentEvent> emit) {
        return new StreamEventForwarder(emit, true);
    }

    /** 后台调用（压缩摘要）：只转发重试通知。 */
    static StreamEventForwarder retryNoticesOnly(Consumer<AgentEvent> emit) {
        return new StreamEventForwarder(emit, false);
    }

    @Override
    public void onText(String delta) {
        if (forwardContent) {
            emit.accept(new AgentEvent.TextDelta(delta));
        }
    }

    @Override
    public void onThinking(String delta) {
        if (forwardContent) {
            emit.accept(new AgentEvent.ThinkingDelta(delta));
        }
    }

    @Override
    public void onToolCallStart(String id, String name) {
        if (forwardContent) {
            emit.accept(new AgentEvent.ToolCallStreaming(id, name));
        }
    }

    @Override
    public void onRetry(int attempt, long delayMillis, String error) {
        emit.accept(new AgentEvent.Retrying(attempt, delayMillis, error));
    }

    @Override
    public void onMalformedOutput(String error) {
        emit.accept(AgentEvent.Notice.warning("malformed model output, retrying the turn (discard the partial reply): " + error));
    }
}
