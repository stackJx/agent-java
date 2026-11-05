/**
 * 会话聚合：对话历史、消息与内容块。
 *
 * <p>先读 {@link com.agentcore.domain.conversation.Conversation}，它保证"发给模型的历史永远合法"：
 * 模型发起的每个工具调用都必须由紧随其后的一条用户消息逐一给出结果。所有修改都会产生领域事件（见 {@code event} 子包），
 * 仓储把事件追加写入存储。详见 {@code docs/03-conversation.md}。
 *
 * <ul>
 *   <li>{@link com.agentcore.domain.conversation.Message}：一条消息 = 角色 + 内容块列表 + 厂商原始内容；</li>
 *   <li>{@link com.agentcore.domain.conversation.ContentBlock}：五种内容块（文字、图片、思考、工具调用、工具结果），sealed 接口；</li>
 *   <li>{@link com.agentcore.domain.conversation.ProviderPayload}：厂商原始内容，回放给同一厂商时原样发送；</li>
 *   <li>{@link com.agentcore.domain.conversation.ConversationRepository}：仓储端口，由基础设施层实现。</li>
 * </ul>
 */
package com.agentcore.domain.conversation;
