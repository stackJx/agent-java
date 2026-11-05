package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.Role;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.llm.model.ToolSpec;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * 领域消息 → OpenAI Responses API（{@code POST /responses}）的 {@code input} 条目。
 *
 * <p>本厂商产生的助手消息按原始 {@code output} 条目回放：加密推理（encrypted_content）、
 * {@code phase}、内置工具调用等都原样带回，无需服务端保存会话（{@code store: false}）。
 * 其他厂商产生的助手消息转成通用的 message / function_call 条目。
 */
public final class OpenAiResponsesWire {

    public static final String PROVIDER = "openai_responses";

    private OpenAiResponsesWire() {
    }

    public static ArrayNode toInput(List<Message> messages) {
        ArrayNode input = Json.array();
        for (Message message : messages) {
            if (message.role() == Role.ASSISTANT) {
                if (message.providerPayload() != null && PROVIDER.equals(message.providerPayload().provider())) {
                    Json.read(message.providerPayload().rawContentJson()).forEach(input::add); // 原样回放
                } else {
                    addAssistant(message, input);
                }
            } else {
                addUser(message, input);
            }
        }
        return input;
    }

    /** 其他厂商（或手工构造）的助手消息：文本 + 函数调用；没有签名的思考内容不发。 */
    private static void addAssistant(Message message, ArrayNode input) {
        String text = message.text();
        if (!text.isEmpty()) {
            input.addObject().put("type", "message").put("role", "assistant").put("content", text);
        }
        for (ToolCall call : message.toolCalls()) {
            input.addObject().put("type", "function_call").put("call_id", call.id()).put("name", call.name())
                    .put("arguments", call.rawArguments() != null ? call.rawArguments() : Json.write(call.arguments()));
        }
    }

    /** 用户消息：工具结果各自成为 function_call_output 条目；文字和图片合成一条 message 条目。 */
    private static void addUser(Message message, ArrayNode input) {
        ArrayNode parts = Json.array();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case ToolResult result -> input.add(toolOutput(result));
                case TextBlock text when !text.text().isEmpty() -> parts.add(Json.object().put("type", "input_text").put("text", text.text()));
                case ImageBlock image -> parts.add(image(image));
                default -> { }
            }
        }
        if (!parts.isEmpty()) {
            input.addObject().put("type", "message").put("role", "user").set("content", parts);
        }
    }

    static ObjectNode toolOutput(ToolResult result) {
        String text = OpenAiWire.toolResultText(result);
        ObjectNode item = Json.object().put("type", "function_call_output").put("call_id", result.toolCallId());
        if (result.images().isEmpty()) {
            item.put("output", text);
        } else {
            ArrayNode parts = item.putArray("output"); // Responses 的工具结果可以直接带图片
            parts.addObject().put("type", "input_text").put("text", text);
            result.images().forEach(image -> parts.add(image(image)));
        }
        return item;
    }

    static ObjectNode image(ImageBlock image) {
        String url = image.url() != null ? image.url() : "data:" + image.mediaType() + ";base64," + image.data();
        return Json.object().put("type", "input_image").put("image_url", url).put("detail", "auto");
    }

    /** Responses 的函数工具是扁平结构：{type, name, description, parameters}。 */
    public static ArrayNode tools(List<ToolSpec> specs) {
        ArrayNode tools = Json.array();
        for (ToolSpec spec : specs) {
            ObjectNode tool = tools.addObject().put("type", "function").put("name", spec.name()).put("description", spec.description());
            tool.set("parameters", Json.tree(spec.inputSchema()));
        }
        return tools;
    }
}
