/**
 * 业务方接入示例：一个电商客服。
 *
 * <ul>
 *   <li>{@link com.example.agent.OrderTools}：普通 Spring Bean，用 {@code @AgentTool} 提供查订单（只读）和退款（需要审批）两个工具；</li>
 *   <li>{@link com.example.agent.RefundLimitHook}：用钩子实现业务规则，单笔退款超过 100 元转人工；</li>
 *   <li>{@link com.example.agent.SampleAgentApplication}：启动类，配置见 {@code application.yml}。</li>
 * </ul>
 */
package com.example.agent;
