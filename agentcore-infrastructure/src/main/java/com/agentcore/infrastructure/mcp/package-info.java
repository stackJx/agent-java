/**
 * MCP（Model Context Protocol）客户端：把外部 MCP 服务器提供的工具接进来。
 *
 * <ul>
 *   <li>{@link com.agentcore.infrastructure.mcp.McpServerManager}：按配置并发启动多个服务器，单个失败不影响其他；</li>
 *   <li>{@link com.agentcore.infrastructure.mcp.McpClient}：JSON-RPC 会话（初始化、分页列出工具、调用工具、响应服务端请求）；</li>
 *   <li>{@link com.agentcore.infrastructure.mcp.StdioTransport} / {@link com.agentcore.infrastructure.mcp.HttpTransport}：子进程或 HTTP 两种传输；</li>
 *   <li>{@link com.agentcore.infrastructure.mcp.McpTool}：把一个远端工具包装成领域 {@code Tool}，名字是 {@code mcp__服务器__工具}。</li>
 * </ul>
 */
package com.agentcore.infrastructure.mcp;
