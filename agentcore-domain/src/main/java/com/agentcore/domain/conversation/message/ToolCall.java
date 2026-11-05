package com.agentcore.domain.conversation.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 模型发起的一次工具调用。
 *
 * @param arguments    解析后的参数（JSON 对象：String/Number/Boolean/List/Map/null）
 * @param rawArguments 原始 JSON 字符串（OpenAI 兼容协议回放时保持字节一致，利于前缀缓存）
 * @param parseError   参数无法解析时的错误信息；非空时该调用不会被执行
 */
public record ToolCall(String id, String name, Map<String, Object> arguments, String rawArguments, String parseError)
        implements ContentBlock {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public static ToolCall of(String id, String name, Map<String, Object> arguments) {
        return new ToolCall(id, name, arguments, null, null);
    }

    @Override
    public String type() {
        return "tool_call";
    }
}
