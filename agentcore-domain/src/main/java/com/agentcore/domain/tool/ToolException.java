package com.agentcore.domain.tool;

/** 工具内抛出此异常 = 返回一个 is_error 结果，消息直接给模型看，让它自行修正。 */
public class ToolException extends RuntimeException {
    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
