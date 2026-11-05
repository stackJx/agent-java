/**
 * 模型适配器。
 *
 * <p>入口是 {@link com.agentcore.infrastructure.llm.ModelGatewayFactory}：把 {@code "provider:model"} 写法变成具体的网关，
 * 按 {@code type} 选择 {@code anthropic}、{@code openai}（Chat Completions）或 {@code openai-responses}（Responses）。
 * {@link com.agentcore.infrastructure.llm.ProviderPresets} 是常用厂商的预设（地址、Key 的环境变量、上下文窗口）。
 * 子包每个对应一种协议，详见 {@code docs/05-model-gateways.md}。
 */
package com.agentcore.infrastructure.llm;
