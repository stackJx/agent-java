package com.agentcore.domain.agent.model;

import com.agentcore.domain.shared.cancel.CancellationToken;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行控制：取消与插话（运行中追加用户输入，在下一个轮次边界交给模型）。
 *
 * <p>插话不会"收下了却没处理"：运行准备正常结束时，引擎调用 {@link #takeSteeringOrClose}，
 * 还有插话就取走、再跑一轮；没有就关闭插话入口，之后的 {@link #steer} 返回 false，调用方知道这段话没送达。
 * 运行因取消、出错、到达轮数上限结束时，没来得及处理的插话由 {@link #close} 交还，引擎会报告出来。
 */
public final class RunControl {

    private final CancellationToken token = new CancellationToken();
    /** 待处理的插话。所有访问都在 this 上同步，保证"取走或关闭"是一个原子操作。 */
    private final List<String> steering = new ArrayList<>();
    private boolean closed;

    public CancellationToken token() {
        return token;
    }

    public void cancel() {
        token.cancel();
    }

    public boolean isCancelled() {
        return token.isCancelled();
    }

    /** 运行中插话。返回 false 表示没收下：文字是空的，或者这次运行已经结束（应该作为新的一条消息发送）。 */
    public synchronized boolean steer(String text) {
        if (closed || text == null || text.isBlank()) {
            return false;
        }
        steering.add(text);
        return true;
    }

    /** 取走所有待处理的插话（引擎在轮次边界调用，宿主不需要调用）。 */
    public synchronized List<String> drainSteering() {
        List<String> taken = List.copyOf(steering);
        steering.clear();
        return taken;
    }

    /**
     * 模型已经说完、运行准备正常结束时调用：有插话就取走（运行继续）；没有就关闭插话入口，返回空列表。
     * 两件事在同一把锁里完成，所以不会有插话在"检查之后、关闭之前"溜进来又被丢掉。
     */
    public synchronized List<String> takeSteeringOrClose() {
        List<String> taken = drainSteering();
        if (taken.isEmpty()) {
            closed = true;
        }
        return taken;
    }

    /** 运行结束：关闭插话入口，交还还没处理的插话（例如运行被取消时用户刚好又说了一句）。 */
    public synchronized List<String> close() {
        closed = true;
        return drainSteering();
    }
}
