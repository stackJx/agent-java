package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.agent.hook.AgentHook;
import com.agentcore.domain.agent.hook.HookDecision;
import com.agentcore.domain.tool.model.ToolOutput;
import java.util.List;

/**
 * 关卡 6、9：调用使用者的 {@link AgentHook}。执行前 beforeTool 可以拦截（原因交给模型）或改写参数；
 * 执行后 afterTool 可以脱敏、改写输出。AgentHook 是给业务方的简单扩展点，本拦截器把它接进链里。
 */
public final class HookInterceptor implements ToolInterceptor {

    private final List<AgentHook> hooks;

    public HookInterceptor(List<AgentHook> hooks) {
        this.hooks = List.copyOf(hooks);
    }

    @Override
    public ToolOutput intercept(ToolInvocation inv, Chain chain) {
        for (AgentHook h : hooks) {
            HookDecision hd = h.beforeTool(inv.call(), inv.tool(), inv.arguments(), inv.context());
            if (hd != null && hd.blocked()) {
                return ToolOutput.error("Blocked by policy hook: " + hd.message());
            }
            if (hd != null && hd.rewrittenArguments() != null) {
                inv.setArguments(hd.rewrittenArguments());
            }
        }
        ToolOutput out = chain.proceed(inv);
        for (AgentHook h : hooks) {
            ToolOutput o = h.afterTool(inv.call(), out, inv.context());
            if (o != null) {
                out = o;
            }
        }
        return out;
    }
}
