/**
 * 引擎对外发出的事件：{@link com.agentcore.domain.agent.event.AgentEvent}（文字增量、工具开始 / 结束、运行结束……）及其监听器。命令行、SSE、--events 都消费它们。
 * {@link com.agentcore.domain.agent.event.RunObserver} 观察所有运行（指标、审计），由引擎统一挂上。
 */
package com.agentcore.domain.agent.event;
