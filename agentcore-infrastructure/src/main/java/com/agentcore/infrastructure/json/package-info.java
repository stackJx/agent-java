/**
 * JSON 编解码（Jackson）。
 *
 * <ul>
 *   <li>{@link com.agentcore.infrastructure.json.DomainCodec}：领域事件、消息、内容块与 JSON 互转，是所有存储共用的格式；</li>
 *   <li>{@link com.agentcore.infrastructure.json.EventCodec}：{@code AgentEvent} 转 JSON，用于 SSE 和 {@code --events} 输出；</li>
 *   <li>{@link com.agentcore.infrastructure.json.Json}：共享的 ObjectMapper 和小工具方法。</li>
 * </ul>
 */
package com.agentcore.infrastructure.json;
