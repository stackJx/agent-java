package com.agentcore.domain.shared.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合根基类：记录聚合内产生的、还没保存的领域事件。
 *
 * <p>仓储保存分两步：先用 {@link #pendingEvents()} 看一眼要写什么，<b>写成功之后</b>再调用 {@link #markEventsSaved}。
 * 写失败时事件仍留在聚合里，下次保存会连同新事件一起重写 —— 不会出现"中间丢了几条事件、后面的却写进去了"的断档历史。
 */
public abstract class AggregateRoot<ID> {

    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    public abstract ID id();

    protected final synchronized void raise(DomainEvent event) {
        pendingEvents.add(event);
    }

    /** 还没保存的领域事件（按产生顺序的副本，不会清空）。 */
    public final synchronized List<DomainEvent> pendingEvents() {
        return List.copyOf(pendingEvents);
    }

    /**
     * 仓储已经把 {@link #pendingEvents()} 返回的这些事件写进了存储：从待保存列表的开头移除它们。
     * 写的过程中又产生的新事件排在后面，保留到下次保存。
     */
    public final synchronized void markEventsSaved(List<DomainEvent> saved) {
        if (saved.size() > pendingEvents.size()) {
            throw new IllegalStateException("cannot mark " + saved.size() + " events as saved, only " + pendingEvents.size() + " are pending");
        }
        for (int i = 0; i < saved.size(); i++) {
            if (pendingEvents.get(i) != saved.get(i)) {
                throw new IllegalStateException("saved events do not match the start of the pending events");
            }
        }
        pendingEvents.subList(0, saved.size()).clear();
    }
}
