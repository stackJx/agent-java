package com.agentcore.domain.shared;

/**
 * 并发修改冲突：保存聚合时发现存储中的版本已被其他进程/实例推进（乐观并发控制）。
 * 调用方应重新加载聚合后再操作。
 */
public class ConcurrencyConflictException extends DomainException {
    public ConcurrencyConflictException(String message) {
        super(message);
    }
}
