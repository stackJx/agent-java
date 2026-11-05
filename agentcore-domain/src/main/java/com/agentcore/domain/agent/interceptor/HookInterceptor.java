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
    public ToolOutput intercept(ToolInvocation invocation, Chain chain) {
        // 6. 执行前：任何一个钩子拦截就不再往下走；改写的参数交给后面的钩子和工具
        for (AgentHook hook : hooks) {
            HookDecision decision = hook.beforeTool(invocation.call(), invocation.tool(), invocation.arguments(), invocation.context());
            if (decision == null) {
                continue;
            }
            if (decision.blocked()) {
                return ToolOutput.error("Blocked by policy hook: " + decision.message());
            }
            if (decision.rewrittenArguments() != null) {
                invocation.setArguments(decision.rewrittenArguments());
            }
        }
        ToolOutput output = chain.proceed(invocation);
        // 9. 执行后：钩子返回 null 表示不改
        for (AgentHook hook : hooks) {
            ToolOutput rewritten = hook.afterTool(invocation.call(), output, invocation.context());
            if (rewritten != null) {
                output = rewritten;
            }
        }
        return output;
    }
}
