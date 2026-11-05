package com.agentcore.interfaces.cli;

/** Ctrl+C 处理：运行中把 SIGINT 转成「取消当前任务」，结束后恢复默认行为。 */
@SuppressWarnings("removal")
final class SignalHandling {

    private SignalHandling() {
    }

    static Object onInterrupt(Runnable action) {
        try {
            return sun.misc.Signal.handle(new sun.misc.Signal("INT"), sig -> action.run());
        } catch (Throwable t) {
            return null; // 平台不支持
        }
    }

    static void restore(Object previous) {
        if (previous instanceof sun.misc.SignalHandler h) {
            try {
                sun.misc.Signal.handle(new sun.misc.Signal("INT"), h);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
