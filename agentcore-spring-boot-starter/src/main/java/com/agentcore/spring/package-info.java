/**
 * Spring Boot 4 自动装配。
 *
 * <ul>
 *   <li>{@link com.agentcore.spring.AgentCoreAutoConfiguration}：把模型、工具、仓储、权限、引擎、{@code AgentService} 注册为 Bean。
 *       每个 Bean 都是 {@code @ConditionalOnMissingBean}，应用自己定义同类型 Bean 就能替换；</li>
 *   <li>{@link com.agentcore.spring.AgentCoreProperties}：{@code agentcore.*} 配置项；</li>
 *   <li>{@link com.agentcore.spring.AgentToolRegistrar}：启动完成后扫描所有 Bean，把 {@code @AgentTool} 方法和实现了 {@code Tool} 的 Bean 注册进工具表；</li>
 *   <li>{@link com.agentcore.spring.ConversationRetention}：设置了 {@code agentcore.sessions.retention} 时定期删除过期会话。</li>
 * </ul>
 * 子包 {@code jdbc}（数据库存储，需显式开启）、{@code web}（REST + SSE 控制器）、{@code metrics}（Micrometer 指标，有 MeterRegistry 时生效）。
 */
package com.agentcore.spring;
