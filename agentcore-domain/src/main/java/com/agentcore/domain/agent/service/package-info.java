/**
 * 领域服务：{@link com.agentcore.domain.agent.service.AgentEngine} 驱动一次运行的主循环 ①～⑩（调模型、按停止原因分支、压缩触发），
 * 工具调用交给 {@code ToolExecutor}（相邻的只读工具并行，每个调用走一遍 {@code agent.interceptor} 的拦截器链），
 * 调模型的退避重试由 {@code llm.gateway.RetryingModelGateway} 装饰器负责。
 */
package com.agentcore.domain.agent.service;
