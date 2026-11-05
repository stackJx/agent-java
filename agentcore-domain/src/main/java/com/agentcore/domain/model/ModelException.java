package com.agentcore.domain.model;

/** 模型调用失败。retryable=true 时引擎会退避重试。 */
public class ModelException extends RuntimeException {

    private final boolean retryable;
    private final Integer status;

    public ModelException(String message, boolean retryable, Integer status, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.status = status;
    }

    public ModelException(String message, boolean retryable) {
        this(message, retryable, null, null);
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer status() {
        return status;
    }
}
