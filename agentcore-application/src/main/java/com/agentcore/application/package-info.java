/**
 * 应用层：对外暴露的用例。目前没有用到框架（只有领域层是硬性要求不依赖框架），由 Spring 或手动装配成 Bean。
 *
 * <ul>
 *   <li>{@link com.agentcore.application.AgentService}：开始会话、同步/异步发消息、取消、插话、压缩、查询。
 *       负责加载和保存聚合，并保证同一会话同一时刻只有一个运行。</li>
 * </ul>
 * 子包：{@code approval}（异步审批）、{@code prompt}（系统提示词）、{@code subagent}（子 Agent）、{@code tool}（记忆和技能工具）。
 */
package com.agentcore.application;
