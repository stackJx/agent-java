/**
 * 会话：对话历史及其不变式。先读 {@code model.Conversation}：它保证"发给模型的历史永远合法"，
 * 模型发起的每个工具调用都必须由紧随其后的一条用户消息逐一给出结果。详见 docs/03-conversation.md。
 *
 * <p>子包：{@code model}、{@code message}、{@code event}、{@code repository}（各子包的 package-info 有说明）。
 */
package com.agentcore.domain.conversation;
