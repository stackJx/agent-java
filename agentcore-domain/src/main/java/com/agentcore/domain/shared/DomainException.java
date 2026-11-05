package com.agentcore.domain.shared;

/** 违反领域不变式时抛出。 */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
