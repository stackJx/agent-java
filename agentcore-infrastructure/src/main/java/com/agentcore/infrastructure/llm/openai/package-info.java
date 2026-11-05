/**
 * OpenAI 协议的两个适配器，都基于 JDK HttpClient + 手写 SSE 解析（便于处理各家的扩展字段）。
 *
 * <ul>
 *   <li>{@link com.agentcore.infrastructure.llm.openai.OpenAiResponsesModelGateway} + {@link com.agentcore.infrastructure.llm.openai.OpenAiResponsesWire}：
 *       Responses API（{@code /responses}），{@code openai:} 默认用它。原始 output 条目（含加密推理）原样回放；</li>
 *   <li>{@link com.agentcore.infrastructure.llm.openai.OpenAiCompatibleModelGateway} + {@link com.agentcore.infrastructure.llm.openai.OpenAiWire}：
 *       Chat Completions（{@code /chat/completions}），DeepSeek、通义千问、Kimi、智谱、vLLM、Ollama 等兼容服务用它；</li>
 *   <li>{@code OpenAiHttp}：两者共用的 HTTP 细节（发流式请求、翻译 HTTP 错误、取消时关闭连接、解析工具参数）。</li>
 * </ul>
 */
package com.agentcore.infrastructure.llm.openai;
