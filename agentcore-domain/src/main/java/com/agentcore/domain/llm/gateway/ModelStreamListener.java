package com.agentcore.domain.llm.gateway;

/** 模型调用过程中的回调：流式增量，以及重试通知。都有默认空实现，按需覆盖。 */
public interface ModelStreamListener {

    ModelStreamListener NOOP = new ModelStreamListener() { };

    default void onText(String delta) {
    }

    default void onThinking(String delta) {
    }

    default void onToolCallStart(String id, String name) {
    }

    /** 可重试的错误（限流 / 5xx / 网络），等 delayMillis 后第 attempt 次重试（由 {@link RetryingModelGateway} 调用）。 */
    default void onRetry(int attempt, long delayMillis, String error) {
    }

    /** 模型输出无法解析，马上重发本轮（由 {@link RetryingModelGateway} 调用）。 */
    default void onMalformedOutput(String error) {
    }
}
