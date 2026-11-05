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
        ArrayNode out = Json.array();
        if (system != null && !system.isEmpty()) {
            out.add(Json.object().put("role", "system").put("content", system));
        }
        for (Message m : messages) {
            if (m.role() == Role.ASSISTANT) {
                ObjectNode msg = Json.object().put("role", "assistant");
                String text = m.text();
                List<ToolCall> calls = m.toolCalls();
                if (text.isEmpty()) {
                    if (calls.isEmpty()) {
                        msg.put("content", "");
                    } else {
                        msg.putNull("content");
                    }
                } else {
                    msg.put("content", text);
                }
                if (!calls.isEmpty()) {
                    ArrayNode tcs = msg.putArray("tool_calls");
                    for (ToolCall c : calls) {
                        ObjectNode tc = tcs.addObject().put("id", c.id()).put("type", "function");
                        tc.putObject("function").put("name", c.name())
                                .put("arguments", c.rawArguments() != null ? c.rawArguments() : Json.write(c.arguments()));
                    }
                }
                if (echoReasoning) {
                    // DeepSeek 思考模式：带 tools 的请求必须回传历史 reasoning_content，否则 400
                    String rc = m.content().stream()
                            .filter(b -> b instanceof ThinkingBlock t && PROVIDER.equals(t.provider()))
                            .map(b -> ((ThinkingBlock) b).text()).collect(Collectors.joining());
                    if (!rc.isEmpty()) {
                        msg.put("reasoning_content", rc);
                    }
                }
                out.add(msg);
                continue;
            }
            List<ObjectNode> parts = new ArrayList<>();
            for (ContentBlock b : m.content()) {
                switch (b) {
                    case ToolResult r -> {
                        String text = r.text().isEmpty() ? (r.isError() ? "(error)" : "(no output)") : r.text();
                        if (r.isError() && !text.toLowerCase().startsWith("error")) {
                            text = "Error: " + text;
                        }
                        out.add(Json.object().put("role", "tool").put("tool_call_id", r.toolCallId()).put("content", text));
                        r.images().forEach(i -> parts.add(imagePart(i))); // tool 消息只支持文本，图片放到随后的 user 消息
                    }
                    case TextBlock t when !t.text().isEmpty() -> parts.add(Json.object().put("type", "text").put("text", t.text()));
                    case ImageBlock i -> parts.add(imagePart(i));
                    default -> { }
                }
            }
            if (!parts.isEmpty()) {
                ObjectNode user = Json.object().put("role", "user");
                if (parts.stream().allMatch(p -> "text".equals(p.path("type").asText()))) {
                    user.put("content", parts.stream().map(p -> p.path("text").asText()).collect(Collectors.joining("\n\n")));
                } else {
                    ArrayNode arr = user.putArray("content");
                    parts.forEach(arr::add);
                }
                out.add(user);
            }
        }
        return out;
    }

    static ObjectNode imagePart(ImageBlock i) {
        String url = i.url() != null ? i.url() : "data:" + i.mediaType() + ";base64," + i.data();
        ObjectNode n = Json.object().put("type", "image_url");
        n.putObject("image_url").put("url", url);
        return n;
    }
}
