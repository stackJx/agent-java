/**
 * 对外的 HTTP + SSE 接口（Spring MVC 注解控制器）。
 *
 * <ul>
 *   <li>{@link com.agentcore.interfaces.web.AgentController}：会话、发消息（SSE 事件流）、取消、插话、审批；
 *       独立部署版和嵌入 Spring Boot 应用用的是同一个控制器，由 starter 的 Web 自动装配注册；</li>
 *   <li>{@link com.agentcore.interfaces.web.WebJson}：审批请求、审批结果的 JSON 转换；</li>
 *   <li>{@link com.agentcore.interfaces.web.BearerTokenInterceptor}：配置了 {@code agentcore.web.token} 时的简单鉴权。</li>
 * </ul>
 * 需要审批时，{@code AsyncApprover} 把请求作为 {@code approval_required} 事件推给当前的 SSE 连接，前端再调用审批接口回传。
 */
package com.agentcore.interfaces.web;
