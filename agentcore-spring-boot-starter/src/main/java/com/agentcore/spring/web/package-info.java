/**
 * Web 接口的自动装配：Servlet 应用里默认开启（{@code agentcore.web.enabled=false} 关闭）。
 * {@link com.agentcore.spring.web.AgentController} 提供与独立版一致的 REST + SSE 接口，路径前缀 {@code agentcore.web.base-path}（默认 {@code /api/agent}）；
 * 同时注册一个 {@code AsyncApprover} 作为审批人，审批请求通过 SSE 推给前端。
 */
package com.agentcore.spring.web;
