package com.agentcore.domain.shared.model;

import java.time.Instant;

/** 领域事件标记接口。 */
public interface DomainEvent {
    Instant occurredAt();
}
