package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.ImageBlock;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.Role;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.conversation.ToolResult;
import com.agentcore.domain.model.ToolSpec;
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
        ArrayNode out = Json.array();
        for (Message m : messages) {
            if (m.role() == Role.ASSISTANT) {
                if (m.providerPayload() != null && PROVIDER.equals(m.providerPayload().provider())) {
                    Json.read(m.providerPayload().rawContentJson()).forEach(out::add); // 原样回放
                } else {
                    assistant(m, out);
                }
                continue;
            }
            ArrayNode parts = Json.array();
            for (ContentBlock b : m.content()) {
                switch (b) {
                    case ToolResult r -> out.add(toolOutput(r));
                    case TextBlock t when !t.text().isEmpty() -> parts.add(Json.object().put("type", "input_text").put("text", t.text()));
                    case ImageBlock i -> parts.add(image(i));
                    default -> { }
                }
            }
            if (!parts.isEmpty()) {
                out.addObject().put("type", "message").put("role", "user").set("content", parts);
            }
        }
        return out;
    }

    /** 其他厂商（或手工构造）的助手消息：文本 + 函数调用；没有签名的思考内容不发。 */
    private static void assistant(Message m, ArrayNode out) {
        String text = m.text();
        if (!text.isEmpty()) {
            out.addObject().put("type", "message").put("role", "assistant").put("content", text);
        }
        for (ToolCall c : m.toolCalls()) {
            out.addObject().put("type", "function_call").put("call_id", c.id()).put("name", c.name())
                    .put("arguments", c.rawArguments() != null ? c.rawArguments() : Json.write(c.arguments()));
        }
    }

    static ObjectNode toolOutput(ToolResult r) {
        String text = r.text().isEmpty() ? (r.isError() ? "(error)" : "(no output)") : r.text();
        if (r.isError() && !text.toLowerCase().startsWith("error")) {
            text = "Error: " + text;
        }
        ObjectNode n = Json.object().put("type", "function_call_output").put("call_id", r.toolCallId());
        if (r.images().isEmpty()) {
            n.put("output", text);
        } else {
            ArrayNode parts = n.putArray("output"); // Responses 的工具结果可以直接带图片
            parts.addObject().put("type", "input_text").put("text", text);
            r.images().forEach(i -> parts.add(image(i)));
        }
        return n;
    }

    static ObjectNode image(ImageBlock i) {
        String url = i.url() != null ? i.url() : "data:" + i.mediaType() + ";base64," + i.data();
        return Json.object().put("type", "input_image").put("image_url", url).put("detail", "auto");
    }

    /** Responses 的函数工具是扁平结构：{type, name, description, parameters}。 */
    public static ArrayNode tools(List<ToolSpec> specs) {
        ArrayNode out = Json.array();
        for (ToolSpec t : specs) {
            ObjectNode fn = out.addObject().put("type", "function").put("name", t.name()).put("description", t.description());
            fn.set("parameters", Json.tree(t.inputSchema()));
        }
        return out;
    }
}
