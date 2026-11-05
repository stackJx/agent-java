/**
 * 大模型端口。领域层只通过 {@link com.agentcore.domain.model.ModelGateway} 和模型对话，不知道具体是哪家厂商。
 *
 * <p>适配器（基础设施层 {@code infrastructure.llm}）负责三件事：把 {@link com.agentcore.domain.model.ModelRequest} 翻译成厂商请求；
 * 流式回调 {@link com.agentcore.domain.model.ModelStreamListener}；把厂商错误翻译成下面三类异常，引擎据此决定怎么处理：
 *
 * <ul>
 *   <li>{@link com.agentcore.domain.model.ModelException}（{@code retryable} 为真时指数退避重试）；</li>
 *   <li>{@link com.agentcore.domain.model.ContextOverflowException}：上下文超窗，压缩后重做本轮；</li>
 *   <li>{@link com.agentcore.domain.model.MalformedOutputException}：输出无法解析，重发本轮。</li>
 * </ul>
 * 详见 {@code docs/05-model-gateways.md}。
 */
package com.agentcore.domain.model;
