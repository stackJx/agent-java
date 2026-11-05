package com.agentcore.domain.conversation.message;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 工具执行结果。content 只允许 {@link TextBlock} 与 {@link ImageBlock}。
 *
 * @param toolName 仅用于展示与日志，不发给模型
 */
public record ToolResult(String toolCallId, String toolName, List<ContentBlock> content, boolean isError)
        implements ContentBlock {

    public ToolResult {
        Objects.requireNonNull(toolCallId, "toolCallId");
        content = List.copyOf(content == null ? List.of() : content);
        for (ContentBlock b : content) {
            if (!(b instanceof TextBlock) && !(b instanceof ImageBlock)) {
                throw new IllegalArgumentException("tool result may only contain text or images");
            }
        }
    }

    public static ToolResult text(String toolCallId, String toolName, String text, boolean isError) {
        return new ToolResult(toolCallId, toolName, List.of(new TextBlock(text)), isError);
    }

    public String text() {
        return content.stream().filter(TextBlock.class::isInstance).map(b -> ((TextBlock) b).text())
                .collect(Collectors.joining("\n"));
    }

    public List<ImageBlock> images() {
        return content.stream().filter(ImageBlock.class::isInstance).map(ImageBlock.class::cast).toList();
    }

    @Override
    public String type() {
        return "tool_result";
    }
}
