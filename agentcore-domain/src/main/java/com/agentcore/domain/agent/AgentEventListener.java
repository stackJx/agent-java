package com.agentcore.domain.agent;

/** 事件监听器。引擎保证串行回调（并行工具产生的事件不会交错调用监听器）。 */
@FunctionalInterface
public interface AgentEventListener {

    AgentEventListener NOOP = e -> { };

    void onEvent(AgentEvent event);

    default AgentEventListener andThen(AgentEventListener other) {
        return e -> {
            onEvent(e);
            other.onEvent(e);
        };
    }
}
