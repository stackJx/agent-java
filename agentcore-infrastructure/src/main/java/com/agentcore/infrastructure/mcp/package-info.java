/**
 * MCP（Model Context Protocol）客户端：把外部 MCP 服务器提供的工具接进来。协议实现用官方 MCP Java SDK。
 *
 * <ul>
 *   <li>{@link com.agentcore.infrastructure.mcp.McpServerManager}：按配置并发启动多个服务器（子进程或 Streamable HTTP），
 *       单个失败不影响其他；收集工具和各服务器的使用说明；</li>
 *   <li>{@link com.agentcore.infrastructure.mcp.McpTool}：把一个远端工具包装成领域 {@code Tool}，名字是 {@code mcp__服务器__工具}。</li>
 * </ul>
 */
package com.agentcore.infrastructure.mcp;
