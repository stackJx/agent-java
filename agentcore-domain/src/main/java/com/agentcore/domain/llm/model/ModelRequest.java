package com.agentcore.domain.llm.model;

import com.agentcore.domain.conversation.message.Message;
import java.util.List;
import java.util.Map;

/**
 * 一次模型请求。
 *
 * @param maxTokens 为 null 时使用网关默认值
 * @param options   单次请求的厂商参数覆盖，例如 {"effort": "low"}
 */
public record ModelRequest(String system, List<Message> messages, List<ToolSpec> tools, Integer maxTokens,
                           Map<String, Object> options) {

    public ModelRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        options = options == null ? Map.of() : Map.copyOf(options);
        system = system == null ? "" : system;
    }

    public static ModelRequest of(String system, List<Message> messages, List<ToolSpec> tools) {
        return new ModelRequest(system, messages, tools, null, Map.of());
    }

}
