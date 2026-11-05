/**
 * 工具调用拦截器链：每个工具调用依次经过这些拦截器，末端才真正执行工具（由 {@code agent.service.ToolExecutor} 驱动）。
 *
 * <ul>
 *   <li>{@link com.agentcore.domain.agent.interceptor.ToolInterceptor}：拦截器接口，可以拦下调用、改参数、加工输出；</li>
 *   <li>{@link com.agentcore.domain.agent.interceptor.ToolInvocation}：在链里传递的一次调用（工具、参数、执行上下文）；</li>
 *   <li>内置的四个，按顺序：{@code ArgumentsInterceptor}（关卡 2、3）→ {@code PermissionInterceptor}（4、5）
 *       → 使用者的拦截器 → {@code HookInterceptor}（6、9）→ {@code TruncateInterceptor}（8）→ 执行（7）。</li>
 * </ul>
 * 想加沙箱检查、调用链追踪、按工具限流、耗时统计，就写一个 ToolInterceptor 加进来，不用改引擎。
 */
package com.agentcore.domain.agent.interceptor;
