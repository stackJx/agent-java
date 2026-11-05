package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.tool.model.ToolOutput;

/**
 * 工具调用拦截器：每个工具调用都会依次经过一条拦截器链，链的末端才真正执行工具。
 *
 * <p>每个拦截器可以：
 * <ul>
 *   <li>直接返回一个结果（通常是 {@link ToolOutput#error}），不再往下走 —— 拦下这次调用；</li>
 *   <li>修改参数（{@link ToolInvocation#setArguments}）后调用 {@code chain.proceed(invocation)} 放行；</li>
 *   <li>拿到 {@code chain.proceed(...)} 的返回值后再加工 —— 例如截断、脱敏、计时。</li>
 * </ul>
 * 任何一关没通过都不要抛异常，而是返回错误结果：它会交还给模型，让模型自己调整做法。取消（CancelledException）照常抛出。
 *
 * <p>默认的链：参数校验 → 权限与审批 → <b>使用者的拦截器</b> → 钩子（beforeTool / afterTool）→ 截断输出 → 执行。
 * 使用者的拦截器通过 {@code AgentEngine.Builder#interceptor} 加入（Spring Boot 里定义 ToolInterceptor 类型的 Bean 即可），
 * 这时参数已经校验过、权限已经批准。实现要线程安全：并行执行的工具会同时经过同一个拦截器。
 */
@FunctionalInterface
public interface ToolInterceptor {

    ToolOutput intercept(ToolInvocation invocation, Chain chain);

    /** 链的剩余部分。 */
    @FunctionalInterface
    interface Chain {
        ToolOutput proceed(ToolInvocation invocation);
    }
}
