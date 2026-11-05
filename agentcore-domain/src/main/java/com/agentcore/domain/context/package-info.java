/**
 * 上下文管理：估算 token 数，快满时压缩。
 *
 * <p>{@link com.agentcore.domain.context.ConversationCompactor} 不删旧消息，而是让模型把旧对话写成交接摘要，
 * 再用"摘要 + 最新用户输入"开启一段新对话：这样历史始终合法，提示缓存也能重新命中。
 * {@link com.agentcore.domain.context.TokenEstimator} 在没有真实用量时按字符数粗估。
 */
package com.agentcore.domain.context;
