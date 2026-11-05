package com.agentcore.domain.agent;

import com.agentcore.domain.shared.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 运行控制：取消与插话（运行中追加用户输入，在下一个轮次边界交给模型）。 */
public final class RunControl {

    private final CancellationToken token = new CancellationToken();
    private final ConcurrentLinkedQueue<String> steering = new ConcurrentLinkedQueue<>();

    public CancellationToken token() {
        return token;
    }

    public void cancel() {
        token.cancel();
    }

    public boolean isCancelled() {
        return token.isCancelled();
    }

    public void steer(String text) {
        if (text != null && !text.isBlank()) {
            steering.add(text);
        }
    }

    List<String> drainSteering() {
        List<String> out = new ArrayList<>();
        String s;
        while ((s = steering.poll()) != null) {
            out.add(s);
        }
        return out;
    }
}
