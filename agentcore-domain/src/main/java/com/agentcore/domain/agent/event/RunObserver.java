package com.agentcore.domain.agent.event;

/**
 * 观察所有运行（指标、审计日志……），由引擎统一挂上，不需要每个调用方自己传。
 *
 * <p>每次运行开始时，引擎调用 {@link #startRun} 为这次运行要一个事件监听器；这次运行的所有事件，
 * 都会同时发给它和调用方传入的监听器。每次运行一个监听器，所以它可以放心地记录"这次运行"自己的状态（开始时间等）。
 * 子 Agent 的运行也会被观察。监听器出错只记日志，不影响运行。
 */
@FunctionalInterface
public interface RunObserver {

    /** 一次运行开始：返回观察这次运行的监听器（不关心时返回 {@link AgentEventListener#NOOP}）。 */
    AgentEventListener startRun(String conversationId);
}
