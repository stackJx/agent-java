/**
 * 大模型：领域层只通过 {@code gateway.ModelGateway} 和模型对话，不知道具体是哪家厂商。
 * 适配器（基础设施层 {@code infrastructure.llm}）负责翻译请求、流式回调增量、把厂商错误翻译成 {@code exception} 里的三类异常。详见 docs/05-model-gateways.md。
 *
 * <p>子包：{@code model}、{@code gateway}、{@code exception}（各子包的 package-info 有说明）。
 */
package com.agentcore.domain.llm;
