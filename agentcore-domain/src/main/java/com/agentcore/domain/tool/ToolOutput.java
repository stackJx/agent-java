package com.agentcore.domain.tool;

import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.TextBlock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具输出。content 为 Text/Image 块；metadata 给 UI/日志用，不发给模型
 * （约定键 "usage" 放 {@link com.agentcore.domain.conversation.TokenUsage}，会计入本次运行的用量）。
 */
public record ToolOutput(List<ContentBlock> content, boolean isError, Map<String, Object> metadata) {

    public ToolOutput {
        content = List.copyOf(content);
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(List.of(new TextBlock(text)), false, Map.of());
    }

    public static ToolOutput text(String text, Map<String, Object> metadata) {
        return new ToolOutput(List.of(new TextBlock(text)), false, metadata);
    }

    public static ToolOutput error(String message) {
        return new ToolOutput(List.of(new TextBlock(message)), true, Map.of());
    }

    public String text() {
        return content.stream().map(b -> b instanceof TextBlock t ? t.text() : "(image)").collect(Collectors.joining("\n"));
    }

    public ToolOutput withContent(List<ContentBlock> newContent) {
        return new ToolOutput(newContent, isError, metadata);
    }
}
