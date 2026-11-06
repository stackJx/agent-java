package com.agentcore.domain.llm.exception;

import java.time.Duration;

/** 模型调用失败。retryable=true 时引擎会退避重试；服务端给了 Retry-After 时按它等待。 */
public class ModelException extends RuntimeException {

    private final boolean retryable;
    private final Integer status;
    private final Duration retryAfter;

    public ModelException(String message, boolean retryable, Integer status, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.retryable = retryable;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public ModelException(String message, boolean retryable, Integer status, Throwable cause) {
        this(message, retryable, status, cause, null);
    }

    public ModelException(String message, boolean retryable) {
        this(message, retryable, null, null, null);
    }

    public boolean retryable() {
        return retryable;
    }

    public Integer status() {
        return status;
    }

    /** 服务端建议的重试等待时间（HTTP Retry-After）；没有给出时为 null。 */
    public Duration retryAfter() {
        return retryAfter;
    }
}
