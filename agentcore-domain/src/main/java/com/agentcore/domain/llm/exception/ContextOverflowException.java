package com.agentcore.domain.llm.exception;

/** 请求超出上下文窗口：引擎会先压缩上下文再重试。 */
public class ContextOverflowException extends ModelException {
    public ContextOverflowException(String message, Throwable cause) {
        super(message, false, 400, cause);
    }
}
