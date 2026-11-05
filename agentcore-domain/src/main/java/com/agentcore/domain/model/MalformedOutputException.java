package com.agentcore.domain.model;

/** 模型输出无法解析（例如流式工具参数 JSON 损坏、没有可回复的 tool id），引擎会重发本轮。 */
public class MalformedOutputException extends ModelException {
    public MalformedOutputException(String message, Throwable cause) {
        super(message, true, null, cause);
    }
}
