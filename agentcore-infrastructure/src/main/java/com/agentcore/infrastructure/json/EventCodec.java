package com.agentcore.infrastructure.json;

import com.agentcore.domain.agent.AgentEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** AgentEvent → JSON（SSE / 日志 / --events 输出）。 */
public final class EventCodec {

    private EventCodec() {
    }

    public static ObjectNode encode(AgentEvent e) {
        ObjectNode n = Json.object();
        n.put("type", e.type());
        switch (e) {
            case AgentEvent.RunStarted r -> {
                n.put("conversation_id", r.conversationId());
                n.put("agent", r.agent());
                n.put("model", r.model());
            }
            case AgentEvent.TurnStarted t -> n.put("turn", t.turn());
            case AgentEvent.TextDelta t -> n.put("text", t.text());
            case AgentEvent.ThinkingDelta t -> n.put("text", t.text());
            case AgentEvent.ToolCallStreaming t -> {
                n.put("id", t.id());
                n.put("name", t.name());
            }
            case AgentEvent.AssistantMessageCompleted a -> {
                n.set("message", DomainCodec.message(a.message()));
                n.set("usage", DomainCodec.usage(a.usage()));
                n.put("stop_reason", a.stopReason().wire());
            }
            case AgentEvent.ToolStarted t -> {
                n.set("call", DomainCodec.block(t.call()));
                n.put("summary", t.summary());
            }
            case AgentEvent.ToolProgress p -> {
                n.put("call_id", p.callId());
                n.put("message", p.message());
            }
            case AgentEvent.PermissionDecided p -> {
                n.put("call_id", p.callId());
                n.put("tool", p.tool());
                n.put("decision", p.decision());
                n.put("reason", p.reason());
            }
            case AgentEvent.ToolFinished f -> {
                n.put("call_id", f.call().id());
                n.put("tool", f.call().name());
                n.put("output", f.output());
                n.put("is_error", f.isError());
                n.put("duration_ms", f.durationMillis());
                n.set("metadata", Json.tree(f.metadata()));
            }
            case AgentEvent.Compacted c -> {
                n.put("tokens_before", c.tokensBefore());
                n.put("tokens_after", c.tokensAfter());
                n.put("summary", c.summary());
            }
            case AgentEvent.Retrying r -> {
                n.put("attempt", r.attempt());
                n.put("delay_ms", r.delayMillis());
                n.put("error", r.error());
            }
            case AgentEvent.Notice no -> {
                n.put("level", no.level());
                n.put("message", no.message());
            }
            case AgentEvent.RunFinished f -> {
                n.put("stop_reason", f.stopReason().wire());
                n.put("text", f.text());
                n.put("turns", f.turns());
                n.set("usage", DomainCodec.usage(f.usage()));
                if (f.error() != null) {
                    n.put("error", f.error());
                }
            }
        }
        return n;
    }
}
