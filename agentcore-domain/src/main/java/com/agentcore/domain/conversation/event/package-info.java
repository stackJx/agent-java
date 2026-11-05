/**
 * 会话的领域事件。{@code Conversation} 的每一次变化对应一个事件，事件同时也是存储格式：
 * 按顺序重放全部事件（{@code Conversation.replay}）就能还原会话。
 *
 * <ul>
 *   <li>{@link com.agentcore.domain.conversation.event.ConversationStarted}：第一个事件，带会话 ID 和初始元数据；</li>
 *   <li>{@link com.agentcore.domain.conversation.event.MessageAppended}：追加一条消息（助手消息带本次用量）；</li>
 *   <li>{@link com.agentcore.domain.conversation.event.ConversationCompacted}：压缩，用摘要后的新历史整体替换；</li>
 *   <li>{@link com.agentcore.domain.conversation.event.UsageRecorded}：额外用量（压缩、子 Agent）；</li>
 *   <li>{@link com.agentcore.domain.conversation.event.MetadataUpdated}：修改一个元数据。</li>
 * </ul>
 */
package com.agentcore.domain.conversation.event;
