package com.agentcore.domain.agent.hook;

import java.util.Map;

/** before_tool 钩子的决定：放行 / 拦截（message 返回给模型）/ 改写参数。 */
public record HookDecision(boolean blocked, String message, Map<String, Object> rewrittenArguments) {

    private static final HookDecision PROCEED = new HookDecision(false, "", null);

    public static HookDecision proceed() {
        return PROCEED;
    }

    public static HookDecision block(String message) {
        return new HookDecision(true, message, null);
    }

    public static HookDecision rewrite(Map<String, Object> arguments) {
        return new HookDecision(false, "", arguments);
    }
}
