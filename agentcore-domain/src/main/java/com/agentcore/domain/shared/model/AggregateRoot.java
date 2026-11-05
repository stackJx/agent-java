package com.agentcore.domain.shared.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合根基类：记录聚合内产生的领域事件，由仓储在保存时取走（{@link #pullEvents()}）。
 */
public abstract class AggregateRoot<ID> {

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    public abstract ID id();

    protected final synchronized void raise(DomainEvent event) {
        pendingEvents.add(event);
    }

    /** 取走并清空待发布的领域事件。 */
    public final synchronized List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return copy;
    }
}
