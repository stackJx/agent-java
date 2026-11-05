/**
 * Agent 主循环（领域服务）。整个项目的核心逻辑在这里。
 *
 * <p>先读 {@link com.agentcore.domain.agent.AgentEngine}：内部类 {@code Run#execute} 就是"调模型 → 执行工具 → 再调模型"的循环，
 * 代码注释里的 ①～⑩ 与 {@code docs/02-agent-loop.md} 的流程图一一对应。
 *
 * <ul>
 *   <li>{@link com.agentcore.domain.agent.AgentEngine}：主循环、模型重试、工具分批并行执行、压缩触发；</li>
 *   <li>{@link com.agentcore.domain.agent.AgentEvent}：引擎对外发出的全部事件（文字增量、工具开始/结束、运行结束……）；</li>
 *   <li>{@link com.agentcore.domain.agent.AgentHook}：在模型调用、工具调用前后插入业务规则；</li>
 *   <li>{@link com.agentcore.domain.agent.RunControl}：宿主用来取消运行、运行中插话；</li>
 *   <li>{@link com.agentcore.domain.agent.AgentDefinition} / {@link com.agentcore.domain.agent.EngineSettings}：Agent 的名字、系统提示词和运行参数。</li>
 * </ul>
 */
package com.agentcore.domain.agent;
