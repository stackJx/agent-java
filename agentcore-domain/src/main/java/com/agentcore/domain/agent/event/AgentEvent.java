package com.agentcore.domain.agent.event;

import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.model.StopReason;
import java.util.Map;

/**
 * Agent 运行过程中对外发出的事件。CLI、Web（SSE/WebSocket）、日志只需要消费这些事件，
 * 不需要关心底层是哪家模型。
 */
public sealed interface AgentEvent {

    /** 事件类型（snake_case，序列化用）。 */
    String type();

    record RunStarted(String conversationId, String agent, String model) implements AgentEvent {
        public String type() {
            return "run_started";
        }
    }

    record TurnStarted(int turn) implements AgentEvent {
        public String type() {
            return "turn_started";
        }
    }

    record TextDelta(String text) implements AgentEvent {
        public String type() {
            return "text_delta";
        }
    }

    record ThinkingDelta(String text) implements AgentEvent {
        public String type() {
            return "thinking_delta";
        }
    }

    /** 模型正在生成某个工具调用（参数尚未完整）。 */
    record ToolCallStreaming(String id, String name) implements AgentEvent {
        public String type() {
            return "tool_call_streaming";
        }
    }

    record AssistantMessageCompleted(Message message, TokenUsage usage, StopReason stopReason) implements AgentEvent {
        public String type() {
            return "assistant_message";
        }
    }

    record ToolStarted(ToolCall call, String summary) implements AgentEvent {
        public String type() {
            return "tool_started";
        }
    }

    record ToolProgress(String callId, String message) implements AgentEvent {
        public String type() {
            return "tool_progress";
        }
    }

    /** 审批的结论。waitMillis：等审批人回答花了多久（排队 + 等待），用于统计审批耗时。 */
    record PermissionDecided(String callId, String tool, String decision, String reason, long waitMillis) implements AgentEvent {
        public String type() {
            return "permission_decided";
        }
    }

    record ToolFinished(ToolCall call, String output, boolean isError, long durationMillis, Map<String, Object> metadata)
            implements AgentEvent {
        public String type() {
            return "tool_finished";
        }
    }

    record Compacted(long tokensBefore, long tokensAfter, String summary) implements AgentEvent {
        public String type() {
            return "compacted";
        }
    }

    record Retrying(int attempt, long delayMillis, String error) implements AgentEvent {
        public String type() {
            return "retrying";
        }
    }

    /** 非致命提示：level = info / warning / error。 */
    record Notice(String level, String message) implements AgentEvent {
        public static Notice info(String message) {
            return new Notice("info", message);
        }

        public static Notice warning(String message) {
            return new Notice("warning", message);
        }

        public static Notice error(String message) {
            return new Notice("error", message);
        }

        public String type() {
            return "notice";
        }
    }

    record RunFinished(StopReason stopReason, String text, int turns, TokenUsage usage, String error) implements AgentEvent {
        public String type() {
            return "run_finished";
        }
    }
}
