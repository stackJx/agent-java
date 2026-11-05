/**
 * 模型调用的异常，引擎据此决定怎么处理：可重试的 {@code ModelException} 退避重试；{@code ContextOverflowException} 压缩后重做本轮；{@code MalformedOutputException} 重发本轮。
 */
package com.agentcore.domain.llm.exception;
