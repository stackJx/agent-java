package com.agentcore.infrastructure.json;

import com.agentcore.domain.agent.event.AgentEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** AgentEvent → JSON（SSE / 日志 / --events 输出）。 */
public final class EventCodec {

    private EventCodec() {
    }

    public static ObjectNode encode(AgentEvent event) {
        ObjectNode json = Json.object();
        json.put("type", event.type());
        switch (event) {
            case AgentEvent.RunStarted started -> {
                json.put("conversation_id", started.conversationId());
                json.put("agent", started.agent());
                json.put("model", started.model());
            }
            case AgentEvent.TurnStarted turn -> json.put("turn", turn.turn());
            case AgentEvent.TextDelta delta -> json.put("text", delta.text());
            case AgentEvent.ThinkingDelta delta -> json.put("text", delta.text());
            case AgentEvent.ToolCallStreaming streaming -> {
                json.put("id", streaming.id());
                json.put("name", streaming.name());
            }
            case AgentEvent.AssistantMessageCompleted completed -> {
                json.set("message", DomainCodec.message(completed.message()));
                json.set("usage", DomainCodec.usage(completed.usage()));
                json.put("stop_reason", completed.stopReason().wire());
            }
            case AgentEvent.ToolStarted started -> {
                json.set("call", DomainCodec.block(started.call()));
                json.put("summary", started.summary());
            }
            case AgentEvent.ToolProgress progress -> {
                json.put("call_id", progress.callId());
                json.put("message", progress.message());
            }
            case AgentEvent.PermissionDecided decided -> {
                json.put("call_id", decided.callId());
                json.put("tool", decided.tool());
                json.put("decision", decided.decision());
                json.put("reason", decided.reason());
            }
            case AgentEvent.ToolFinished finished -> {
                json.put("call_id", finished.call().id());
                json.put("tool", finished.call().name());
                json.put("output", finished.output());
                json.put("is_error", finished.isError());
                json.put("duration_ms", finished.durationMillis());
                json.set("metadata", Json.tree(finished.metadata()));
            }
            case AgentEvent.Compacted compacted -> {
                json.put("tokens_before", compacted.tokensBefore());
                json.put("tokens_after", compacted.tokensAfter());
                json.put("summary", compacted.summary());
            }
            case AgentEvent.Retrying retrying -> {
                json.put("attempt", retrying.attempt());
                json.put("delay_ms", retrying.delayMillis());
                json.put("error", retrying.error());
            }
            case AgentEvent.Notice notice -> {
                json.put("level", notice.level());
                json.put("message", notice.message());
            }
            case AgentEvent.RunFinished finished -> {
                json.put("stop_reason", finished.stopReason().wire());
                json.put("text", finished.text());
                json.put("turns", finished.turns());
                json.set("usage", DomainCodec.usage(finished.usage()));
                if (finished.error() != null) {
                    json.put("error", finished.error());
                }
            }
        }
        return json;
    }
}
