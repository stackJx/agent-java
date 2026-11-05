package com.agentcore.domain.conversation;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 一条消息（值对象，不可变）。 */
public record Message(Role role, List<ContentBlock> content, ProviderPayload providerPayload) {

    public Message {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content == null ? List.of() : content);
    }

    public static Message user(String text) {
        return new Message(Role.USER, List.of(new TextBlock(text)), null);
    }

    public static Message user(List<? extends ContentBlock> content) {
        return new Message(Role.USER, List.copyOf(content), null);
    }

    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, List.of(new TextBlock(text)), null);
    }

    public static Message assistant(List<? extends ContentBlock> content, ProviderPayload payload) {
        return new Message(Role.ASSISTANT, List.copyOf(content), payload);
    }

    public String text() {
        return content.stream().filter(TextBlock.class::isInstance).map(b -> ((TextBlock) b).text())
                .collect(Collectors.joining());
    }

    public String thinking() {
        return content.stream().filter(ThinkingBlock.class::isInstance).map(b -> ((ThinkingBlock) b).text())
                .filter(Objects::nonNull).collect(Collectors.joining());
    }

    public List<ToolCall> toolCalls() {
        return content.stream().filter(ToolCall.class::isInstance).map(ToolCall.class::cast).toList();
    }

    public List<ToolResult> toolResults() {
        return content.stream().filter(ToolResult.class::isInstance).map(ToolResult.class::cast).toList();
    }

    /** 用户直接输入的消息（不是工具结果）。 */
    public boolean isPlainUserInput() {
        return role == Role.USER && toolResults().isEmpty();
    }
}
