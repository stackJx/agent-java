package com.agentcore.infrastructure.llm.anthropic;

import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.ImageBlock;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.Role;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ThinkingBlock;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.conversation.ToolResult;
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
        List<WireMessage> out = new ArrayList<>();
        for (Message m : messages) {
            if (m.role() == Role.ASSISTANT) {
                List<JsonNode> content = new ArrayList<>();
                if (m.providerPayload() != null && PROVIDER.equals(m.providerPayload().provider())) {
                    Json.read(m.providerPayload().rawContentJson()).forEach(content::add); // 原样回放
                } else {
                    for (ContentBlock b : m.content()) {
                        switch (b) {
                            case TextBlock t when !t.text().isEmpty() -> content.add(text(t.text()));
                            case ToolCall c -> {
                                ObjectNode n = Json.object().put("type", "tool_use").put("id", c.id()).put("name", c.name());
                                n.set("input", Json.tree(c.arguments()));
                                content.add(n);
                            }
                            case ThinkingBlock t when PROVIDER.equals(t.provider()) -> {
                                if (t.redacted()) {
                                    content.add(Json.object().put("type", "redacted_thinking").put("data", t.redactedData()));
                                } else if (t.signature() != null) {
                                    content.add(Json.object().put("type", "thinking").put("thinking", t.text()).put("signature", t.signature()));
                                }
                            }
                            default -> {
                                // 其他厂商的思考内容没有签名，不能发给 Claude
                            }
                        }
                    }
                }
                if (!content.isEmpty()) {
                    out.add(new WireMessage(Role.ASSISTANT, content));
                }
                continue;
            }
            List<JsonNode> results = new ArrayList<>();
            List<JsonNode> others = new ArrayList<>();
            for (ContentBlock b : m.content()) {
                switch (b) {
                    case ToolResult r -> results.add(toolResult(r));
                    case TextBlock t when !t.text().isEmpty() -> others.add(text(t.text()));
                    case ImageBlock i -> others.add(image(i));
                    default -> { }
                }
            }
            List<JsonNode> content = new ArrayList<>(results); // tool_result 必须排在用户消息最前面
            content.addAll(others);
            if (!content.isEmpty()) {
                out.add(new WireMessage(Role.USER, content));
            }
        }
        return out;
    }

    static ObjectNode text(String text) {
        return Json.object().put("type", "text").put("text", text);
    }

    static ObjectNode image(ImageBlock i) {
        ObjectNode n = Json.object().put("type", "image");
        ObjectNode src = n.putObject("source");
        if (i.url() != null) {
            src.put("type", "url").put("url", i.url());
        } else {
            src.put("type", "base64").put("media_type", i.mediaType()).put("data", i.data());
        }
        return n;
    }

    static ObjectNode toolResult(ToolResult r) {
        ObjectNode n = Json.object().put("type", "tool_result").put("tool_use_id", r.toolCallId());
        List<ContentBlock> parts = r.content().stream()
                .filter(b -> !(b instanceof TextBlock t && t.text().isEmpty())).toList();
        if (parts.isEmpty()) {
            n.put("content", "(no output)");
        } else if (parts.stream().allMatch(TextBlock.class::isInstance)) {
            n.put("content", r.text());
        } else {
            ArrayNode arr = n.putArray("content");
            for (ContentBlock b : parts) {
                arr.add(b instanceof TextBlock t ? text(t.text()) : image((ImageBlock) b));
            }
        }
        if (r.isError()) {
            n.put("is_error", true);
        }
        return n;
    }

    /** 响应中的原始块 → 领域内容块（server_tool_use、fallback 等只保留在原始载荷里）。 */
    public static List<ContentBlock> fromWire(List<JsonNode> raw) {
        List<ContentBlock> out = new ArrayList<>();
        for (JsonNode b : raw) {
            switch (b.path("type").asText()) {
                case "text" -> out.add(new TextBlock(b.path("text").asText("")));
                case "thinking" -> out.add(new ThinkingBlock(b.path("thinking").asText(""), Json.text(b, "signature"),
                        false, null, PROVIDER));
                case "redacted_thinking" -> out.add(new ThinkingBlock("", null, true, Json.text(b, "data"), PROVIDER));
                case "tool_use" -> {
                    JsonNode input = b.get("input");
                    if (input != null && input.isObject()) {
                        out.add(new ToolCall(b.path("id").asText(), b.path("name").asText(), Json.toMap(input), null, null));
                    } else {
                        out.add(new ToolCall(b.path("id").asText(), b.path("name").asText(), null, null,
                                "tool input is not a JSON object: " + input));
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    /**
     * 服务端拒答回退（mid-output fallback）后的回放规则：最后一个 fallback 块之前的
     * thinking / redacted_thinking / tool_use、未配对的 server_tool_use 以及其他未识别的内部块都去掉；
     * text、配对完整的服务端工具块和边界之后的内容原样保留。
     */
    public static List<JsonNode> applyFallbackEchoRule(List<JsonNode> blocks) {
        int last = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if ("fallback".equals(blocks.get(i).path("type").asText())) {
                last = i;
            }
        }
        if (last < 0) {
            return blocks;
        }
        Set<String> resultIds = new HashSet<>();
        for (int i = 0; i < last; i++) {
            JsonNode b = blocks.get(i);
            if (b.path("type").asText().endsWith("_tool_result")) {
                resultIds.add(b.path("tool_use_id").asText());
            }
        }
        List<JsonNode> kept = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            JsonNode b = blocks.get(i);
            if (i < last) {
                String t = b.path("type").asText();
                boolean keep = switch (t) {
                    case "text", "fallback" -> true;
                    case "server_tool_use" -> resultIds.contains(b.path("id").asText());
                    default -> t.endsWith("_tool_result");
                };
                if (!keep) {
                    continue;
                }
            }
            kept.add(b);
        }
        return kept;
    }
}
