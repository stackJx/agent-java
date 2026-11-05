package com.agentcore.infrastructure.json;

import com.agentcore.domain.conversation.event.ConversationCompacted;
import com.agentcore.domain.conversation.event.ConversationStarted;
import com.agentcore.domain.conversation.event.MessageAppended;
import com.agentcore.domain.conversation.event.MetadataUpdated;
import com.agentcore.domain.conversation.event.UsageRecorded;
import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.ProviderPayload;
import com.agentcore.domain.conversation.message.Role;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ThinkingBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.message.ToolResult;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.shared.model.DomainEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 领域对象 ↔ JSON。领域层保持零依赖，序列化格式集中定义在这里（持久化与事件推送共用）。 */
public final class DomainCodec {

    private DomainCodec() {
    }

    // ------------------------------------------------------------------ 内容块 / 消息

    public static ObjectNode block(ContentBlock b) {
        ObjectNode n = Json.object();
        n.put("type", b.type());
        switch (b) {
            case TextBlock t -> n.put("text", t.text());
            case ImageBlock i -> {
                putIf(n, "media_type", i.mediaType());
                putIf(n, "data", i.data());
                putIf(n, "url", i.url());
            }
            case ThinkingBlock t -> {
                putIf(n, "text", t.text());
                putIf(n, "signature", t.signature());
                if (t.redacted()) {
                    n.put("redacted", true);
                }
                putIf(n, "redacted_data", t.redactedData());
                putIf(n, "provider", t.provider());
            }
            case ToolCall c -> {
                n.put("id", c.id());
                n.put("name", c.name());
                n.set("arguments", Json.tree(c.arguments()));
                putIf(n, "raw_arguments", c.rawArguments());
                putIf(n, "parse_error", c.parseError());
            }
            case ToolResult r -> {
                n.put("tool_call_id", r.toolCallId());
                putIf(n, "tool_name", r.toolName());
                ArrayNode arr = n.putArray("content");
                r.content().forEach(x -> arr.add(block(x)));
                n.put("is_error", r.isError());
            }
        }
        return n;
    }

    public static ContentBlock block(JsonNode n) {
        String type = Json.text(n, "type");
        return switch (type == null ? "" : type) {
            case "text" -> new TextBlock(n.path("text").asText(""));
            case "image" -> new ImageBlock(Json.text(n, "media_type"), Json.text(n, "data"), Json.text(n, "url"));
            case "thinking" -> new ThinkingBlock(Json.text(n, "text"), Json.text(n, "signature"),
                    n.path("redacted").asBoolean(false), Json.text(n, "redacted_data"), Json.text(n, "provider"));
            case "tool_call" -> new ToolCall(n.path("id").asText(), n.path("name").asText(), Json.toMap(n.get("arguments")),
                    Json.text(n, "raw_arguments"), Json.text(n, "parse_error"));
            case "tool_result" -> {
                List<ContentBlock> content = new ArrayList<>();
                n.path("content").forEach(x -> content.add(block(x)));
                yield new ToolResult(n.path("tool_call_id").asText(), Json.text(n, "tool_name"), content,
                        n.path("is_error").asBoolean(false));
            }
            default -> throw new IllegalArgumentException("unknown block type: " + type);
        };
    }

    public static ObjectNode message(Message m) {
        ObjectNode n = Json.object();
        n.put("role", m.role().wire());
        ArrayNode arr = n.putArray("content");
        m.content().forEach(b -> arr.add(block(b)));
        if (m.providerPayload() != null) {
            ProviderPayload p = m.providerPayload();
            ObjectNode raw = n.putObject("provider_raw");
            raw.put("provider", p.provider());
            putIf(raw, "model", p.model());
            raw.set("content", p.rawContentJson() == null ? Json.array() : Json.read(p.rawContentJson()));
        }
        return n;
    }

    public static Message message(JsonNode n) {
        Role role = Role.valueOf(n.path("role").asText("user").toUpperCase());
        List<ContentBlock> content = new ArrayList<>();
        n.path("content").forEach(x -> content.add(block(x)));
        ProviderPayload payload = null;
        JsonNode raw = n.get("provider_raw");
        if (raw != null && raw.isObject()) {
            payload = new ProviderPayload(raw.path("provider").asText(), Json.text(raw, "model"), Json.write(raw.path("content")));
        }
        return new Message(role, content, payload);
    }

    public static ObjectNode usage(TokenUsage u) {
        ObjectNode n = Json.object();
        n.put("input_tokens", u.inputTokens());
        n.put("output_tokens", u.outputTokens());
        n.put("cache_read_tokens", u.cacheReadTokens());
        n.put("cache_write_tokens", u.cacheWriteTokens());
        n.put("requests", u.requests());
        return n;
    }

    public static TokenUsage usage(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        return new TokenUsage(n.path("input_tokens").asLong(), n.path("output_tokens").asLong(),
                n.path("cache_read_tokens").asLong(), n.path("cache_write_tokens").asLong(), n.path("requests").asInt());
    }

    // ------------------------------------------------------------------ 领域事件（会话存储格式）

    public static ObjectNode event(DomainEvent e) {
        ObjectNode n = Json.object();
        n.put("ts", e.occurredAt().toString());
        switch (e) {
            case ConversationStarted s -> {
                n.put("t", "started");
                n.put("id", s.conversationId().value());
                n.set("metadata", Json.tree(s.metadata()));
            }
            case MessageAppended m -> {
                n.put("t", "msg");
                n.set("m", message(m.message()));
                if (m.usage() != null) {
                    n.set("usage", usage(m.usage()));
                }
            }
            case ConversationCompacted c -> {
                n.put("t", "compacted");
                n.put("summary", c.summary());
                ArrayNode arr = n.putArray("messages");
                c.messages().forEach(m -> arr.add(message(m)));
            }
            case MetadataUpdated mu -> {
                n.put("t", "meta");
                n.put("key", mu.key());
                n.put("value", mu.value());
            }
            case UsageRecorded u -> {
                n.put("t", "usage");
                n.set("usage", usage(u.usage()));
                n.put("reason", u.reason());
            }
            default -> throw new IllegalArgumentException("unsupported event " + e.getClass());
        }
        return n;
    }

    public static DomainEvent event(JsonNode n, ConversationId id) {
        Instant ts = n.hasNonNull("ts") ? Instant.parse(n.get("ts").asText()) : Instant.now();
        return switch (n.path("t").asText()) {
            case "started" -> {
                Map<String, String> meta = new LinkedHashMap<>();
                n.path("metadata").fields().forEachRemaining(f -> meta.put(f.getKey(), f.getValue().asText()));
                yield new ConversationStarted(ConversationId.of(n.path("id").asText(id.value())), meta, ts);
            }
            case "msg" -> new MessageAppended(id, message(n.get("m")), usage(n.get("usage")), ts);
            case "compacted" -> {
                List<Message> msgs = new ArrayList<>();
                n.path("messages").forEach(m -> msgs.add(message(m)));
                yield new ConversationCompacted(id, n.path("summary").asText(), msgs, ts);
            }
            case "usage" -> new UsageRecorded(id, usage(n.get("usage")), n.path("reason").asText(), ts);
            case "meta" -> new MetadataUpdated(id, n.path("key").asText(), n.path("value").asText(), ts);
            default -> null;
        };
    }

    private static void putIf(ObjectNode n, String k, String v) {
        if (v != null) {
            n.put(k, v);
        }
    }
}
