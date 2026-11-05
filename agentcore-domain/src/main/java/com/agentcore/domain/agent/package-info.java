/**
 * Agent 运行：整个项目的核心逻辑。先读 {@code service.AgentEngine} 的内部类 {@code Run}：{@code execute} 是总流程，
 * {@code loop} 一轮一轮地"调模型 → 执行工具 → 再调模型"，{@code decideNext} 决定每轮之后结束还是继续。
 * 各方法上的 ①～⑩ 与 docs/02-agent-loop.md 的流程图一一对应。
 *
 * <p>子包：{@code service}、{@code interceptor}、{@code model}、{@code event}、{@code hook}（各子包的 package-info 有说明）。
 */
package com.agentcore.domain.agent;
