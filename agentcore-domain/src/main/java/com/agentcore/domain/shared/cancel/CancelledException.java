package com.agentcore.domain.shared.cancel;

/** 运行被取消。 */
public class CancelledException extends RuntimeException {
    public CancelledException() {
        super("cancelled");
    }
}
