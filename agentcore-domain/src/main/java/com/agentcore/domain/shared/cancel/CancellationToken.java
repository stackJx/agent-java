package com.agentcore.domain.shared.cancel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 协作式取消令牌。阻塞中的操作（模型流、子进程）可以注册回调，在取消时主动中断自己。
 */
public final class CancellationToken {

    public static final CancellationToken NONE = new CancellationToken();

    private volatile boolean cancelled;
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public void cancel() {
        if (this == NONE || cancelled) {
            return;
        }
        cancelled = true;
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // 回调失败不影响取消
            }
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    /** 注册取消回调；返回一个用于注销的句柄。若已取消则立即执行。 */
    public Runnable onCancel(Runnable callback) {
        if (this == NONE) {
            return () -> { };
        }
        callbacks.add(callback);
        if (cancelled) {
            callback.run();
        }
        return () -> callbacks.remove(callback);
    }
}
