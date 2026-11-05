/**
 * HTTP + SSE 接口，基于 JDK 内置 HttpServer 和虚拟线程，零额外依赖。
 *
 * <p>{@link com.agentcore.interfaces.http.AgentHttpServer} 把发消息的请求变成一个 SSE 事件流：
 * 每个 {@code AgentEvent} 写成一帧，需要审批时推送 {@code approval_required}，前端再调用审批接口回传。
 * {@link com.agentcore.interfaces.http.WebJson} 是请求、响应的 JSON 转换，Spring 版的控制器也复用它。
 */
package com.agentcore.interfaces.http;
