package com.agentcore.infrastructure.llm.anthropic;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.Role;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ThinkingBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 领域消息 ↔ Anthropic Messages API 线上格式（JSON 树）的纯函数转换。
 * 网关再用 SDK 自带的 Jackson 反序列化器把这些 JSON 变成类型化的请求对象。
 */
public final class AnthropicWire {

    public static final String PROVIDER = "anthropic";

    /** 一条线上消息：role + content 块（JSON）。 */
    public record WireMessage(Role role, List<JsonNode> content) {
    }

    private AnthropicWire() {
    }

    public static List<WireMessage> toWire(List<Message> messages) {
        List<WireMessage> wire = new ArrayList<>();
        for (Message message : messages) {
            List<JsonNode> content = message.role() == Role.ASSISTANT ? assistantContent(message) : userContent(message);
            if (!content.isEmpty()) {
                wire.add(new WireMessage(message.role() == Role.ASSISTANT ? Role.ASSISTANT : Role.USER, content));
            }
        }
        return wire;
    }

    /** 助手消息：Claude 自己产生的原样回放（签名、fallback 标记都在里面）；其他来源的按块转换。 */
    private static List<JsonNode> assistantContent(Message message) {
        List<JsonNode> content = new ArrayList<>();
        if (message.providerPayload() != null && PROVIDER.equals(message.providerPayload().provider())) {
            Json.read(message.providerPayload().rawContentJson()).forEach(content::add);
            return content;
        }
        for (ContentBlock block : message.content()) {
            switch (block) {
                case TextBlock text when !text.text().isEmpty() -> content.add(text(text.text()));
                case ToolCall call -> {
                    ObjectNode toolUse = Json.object().put("type", "tool_use").put("id", call.id()).put("name", call.name());
                    toolUse.set("input", Json.tree(call.arguments()));
                    content.add(toolUse);
                }
                case ThinkingBlock thinking when PROVIDER.equals(thinking.provider()) -> {
                    if (thinking.redacted()) {
                        content.add(Json.object().put("type", "redacted_thinking").put("data", thinking.redactedData()));
                    } else if (thinking.signature() != null) {
                        content.add(Json.object().put("type", "thinking").put("thinking", thinking.text())
                                .put("signature", thinking.signature()));
                    }
                }
                default -> {
                    // 其他厂商的思考内容没有签名，不能发给 Claude
                }
            }
        }
        return content;
    }

    /** 用户消息：tool_result 必须排在最前面，文字和图片放在后面。 */
    private static List<JsonNode> userContent(Message message) {
        List<JsonNode> results = new ArrayList<>();
        List<JsonNode> others = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case ToolResult result -> results.add(toolResult(result));
                case TextBlock text when !text.text().isEmpty() -> others.add(text(text.text()));
                case ImageBlock image -> others.add(image(image));
                default -> { }
            }
        }
        List<JsonNode> content = new ArrayList<>(results);
        content.addAll(others);
        return content;
    }

    static ObjectNode text(String text) {
        return Json.object().put("type", "text").put("text", text);
    }

    static ObjectNode image(ImageBlock image) {
        ObjectNode block = Json.object().put("type", "image");
        ObjectNode source = block.putObject("source");
        if (image.url() != null) {
            source.put("type", "url").put("url", image.url());
        } else {
            source.put("type", "base64").put("media_type", image.mediaType()).put("data", image.data());
        }
        return block;
    }

    /** 工具结果：纯文字用字符串，带图片时用块数组；空结果给个占位。 */
    static ObjectNode toolResult(ToolResult result) {
        ObjectNode block = Json.object().put("type", "tool_result").put("tool_use_id", result.toolCallId());
        List<ContentBlock> parts = result.content().stream()
                .filter(part -> !(part instanceof TextBlock text && text.text().isEmpty())).toList();
        if (parts.isEmpty()) {
            block.put("content", "(no output)");
        } else if (parts.stream().allMatch(TextBlock.class::isInstance)) {
            block.put("content", result.text());
        } else {
            ArrayNode content = block.putArray("content");
            for (ContentBlock part : parts) {
                content.add(part instanceof TextBlock text ? text(text.text()) : image((ImageBlock) part));
            }
        }
        if (result.isError()) {
            block.put("is_error", true);
        }
        return block;
    }

    /** 响应中的原始块 → 领域内容块（server_tool_use、fallback 等只保留在原始载荷里）。 */
    public static List<ContentBlock> fromWire(List<JsonNode> raw) {
        List<ContentBlock> content = new ArrayList<>();
        for (JsonNode block : raw) {
            switch (block.path("type").asText()) {
                case "text" -> content.add(new TextBlock(block.path("text").asText("")));
                case "thinking" -> content.add(new ThinkingBlock(block.path("thinking").asText(""), Json.text(block, "signature"),
                        false, null, PROVIDER));
                case "redacted_thinking" -> content.add(new ThinkingBlock("", null, true, Json.text(block, "data"), PROVIDER));
                case "tool_use" -> content.add(toolCall(block));
                default -> { }
            }
        }
        return content;
    }

    /** input 不是对象时不抛异常，记在 parseError 里让引擎回报给模型。 */
    private static ToolCall toolCall(JsonNode block) {
        JsonNode input = block.get("input");
        String id = block.path("id").asText();
        String name = block.path("name").asText();
        if (input != null && input.isObject()) {
            return new ToolCall(id, name, Json.toMap(input), null, null);
        }
        return new ToolCall(id, name, null, null, "tool input is not a JSON object: " + input);
    }

    /**
     * 服务端拒答回退（mid-output fallback）后的回放规则：最后一个 fallback 块之前的
     * thinking / redacted_thinking / tool_use、未配对的 server_tool_use 以及其他未识别的内部块都去掉；
     * text、配对完整的服务端工具块和边界之后的内容原样保留。
     */
    public static List<JsonNode> applyFallbackEchoRule(List<JsonNode> blocks) {
        int lastFallback = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if ("fallback".equals(blocks.get(i).path("type").asText())) {
                lastFallback = i;
            }
        }
        if (lastFallback < 0) {
            return blocks;
        }
        // 边界之前有结果的服务端工具调用才算"配对完整"
        Set<String> answeredServerCalls = new HashSet<>();
        for (int i = 0; i < lastFallback; i++) {
            JsonNode block = blocks.get(i);
            if (block.path("type").asText().endsWith("_tool_result")) {
                answeredServerCalls.add(block.path("tool_use_id").asText());
            }
        }
        List<JsonNode> kept = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            JsonNode block = blocks.get(i);
            if (i >= lastFallback || keepBeforeFallback(block, answeredServerCalls)) {
                kept.add(block);
            }
        }
        return kept;
    }

    private static boolean keepBeforeFallback(JsonNode block, Set<String> answeredServerCalls) {
        String type = block.path("type").asText();
        return switch (type) {
            case "text", "fallback" -> true;
            case "server_tool_use" -> answeredServerCalls.contains(block.path("id").asText());
            default -> type.endsWith("_tool_result");
        };
    }
}
