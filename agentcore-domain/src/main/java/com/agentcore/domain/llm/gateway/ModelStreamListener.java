package com.agentcore.domain.llm.gateway;

/** 流式增量回调。 */
public interface ModelStreamListener {

    ModelStreamListener NOOP = new ModelStreamListener() { };

    default void onText(String delta) {
    }

    default void onThinking(String delta) {
    }

    default void onToolCallStart(String id, String name) {
    }
}
