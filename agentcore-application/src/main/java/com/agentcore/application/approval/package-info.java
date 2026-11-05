/**
 * 异步审批：Web、IM 等非阻塞界面用。
 *
 * <p>{@link com.agentcore.application.approval.AsyncApprover} 把审批请求推给会话当前连接的前端通道，
 * 然后阻塞等待前端回调 {@code resolve}；超时或没有前端连接时拒绝。与具体协议无关。
 * 时序图见 {@code docs/04-tools-and-permissions.md} 第 3 节。
 */
package com.agentcore.application.approval;
