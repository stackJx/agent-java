/**
 * Claude 适配器（官方 anthropic-java SDK，beta 命名空间）。
 *
 * <ul>
 *   <li>{@link com.agentcore.infrastructure.llm.anthropic.AnthropicModelGateway}：流式请求、自适应思考、effort、提示缓存、拒答回退、错误翻译；</li>
 *   <li>{@link com.agentcore.infrastructure.llm.anthropic.AnthropicWire}：领域消息与 Anthropic 内容块互转；本家消息按原始块回放（思考签名不能丢）。</li>
 * </ul>
 */
package com.agentcore.infrastructure.llm.anthropic;
