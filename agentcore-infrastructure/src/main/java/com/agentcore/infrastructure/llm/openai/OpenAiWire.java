package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.Role;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ThinkingBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 领域消息 → OpenAI Chat Completions 消息格式。 */
public final class OpenAiWire {

    public static final String PROVIDER = "openai_compat";

    private OpenAiWire() {
    }

    public static ArrayNode toWire(String system, List<Message> messages, boolean echoReasoning) {
        ArrayNode wire = Json.array();
        if (system != null && !system.isEmpty()) {
            wire.add(Json.object().put("role", "system").put("content", system));
        }
        for (Message message : messages) {
            if (message.role() == Role.ASSISTANT) {
                wire.add(assistantMessage(message, echoReasoning));
            } else {
                addUserMessages(message, wire);
            }
        }
        return wire;
    }

    /** 助手消息：文字放 content（只有工具调用时 content 为 null），工具调用放 tool_calls。 */
    private static ObjectNode assistantMessage(Message message, boolean echoReasoning) {
        ObjectNode wire = Json.object().put("role", "assistant");
        String text = message.text();
        List<ToolCall> calls = message.toolCalls();
        if (!text.isEmpty()) {
            wire.put("content", text);
        } else if (calls.isEmpty()) {
            wire.put("content", "");
        } else {
            wire.putNull("content");
        }
        if (!calls.isEmpty()) {
            ArrayNode toolCalls = wire.putArray("tool_calls");
            for (ToolCall call : calls) {
                ObjectNode toolCall = toolCalls.addObject().put("id", call.id()).put("type", "function");
                toolCall.putObject("function").put("name", call.name())
                        .put("arguments", call.rawArguments() != null ? call.rawArguments() : Json.write(call.arguments()));
            }
        }
        if (echoReasoning) {
            // DeepSeek 思考模式：带 tools 的请求必须回传历史 reasoning_content，否则 400
            String reasoning = message.content().stream()
                    .filter(block -> block instanceof ThinkingBlock thinking && PROVIDER.equals(thinking.provider()))
                    .map(block -> ((ThinkingBlock) block).text()).collect(Collectors.joining());
            if (!reasoning.isEmpty()) {
                wire.put("reasoning_content", reasoning);
            }
        }
        return wire;
    }

    /**
     * 用户消息（领域里工具结果也在用户消息里）：每个工具结果变成一条 role=tool 消息；
     * 文字和图片合成随后的一条 user 消息（tool 消息只支持文本，工具结果里的图片也放到这里）。
     */
    private static void addUserMessages(Message message, ArrayNode wire) {
        List<ObjectNode> parts = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case ToolResult result -> {
                    wire.add(Json.object().put("role", "tool").put("tool_call_id", result.toolCallId()).put("content", toolResultText(result)));
                    result.images().forEach(image -> parts.add(imagePart(image)));
                }
                case TextBlock text when !text.text().isEmpty() -> parts.add(Json.object().put("type", "text").put("text", text.text()));
                case ImageBlock image -> parts.add(imagePart(image));
                default -> { }
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        ObjectNode user = Json.object().put("role", "user");
        if (parts.stream().allMatch(part -> "text".equals(part.path("type").asText()))) {
            // 纯文字时用字符串而不是数组，兼容性最好
            user.put("content", parts.stream().map(part -> part.path("text").asText()).collect(Collectors.joining("\n\n")));
        } else {
            ArrayNode content = user.putArray("content");
            parts.forEach(content::add);
        }
        wire.add(user);
    }

    /** 工具结果的文字：空结果给个占位；出错的结果保证以 "Error" 开头，模型一眼能看出来。 */
    static String toolResultText(ToolResult result) {
        String text = result.text().isEmpty() ? (result.isError() ? "(error)" : "(no output)") : result.text();
        if (result.isError() && !text.toLowerCase().startsWith("error")) {
            text = "Error: " + text;
        }
        return text;
    }

    static ObjectNode imagePart(ImageBlock image) {
        String url = image.url() != null ? image.url() : "data:" + image.mediaType() + ";base64," + image.data();
        ObjectNode part = Json.object().put("type", "image_url");
        part.putObject("image_url").put("url", url);
        return part;
    }
}
