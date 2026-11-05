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

    public static ObjectNode block(ContentBlock block) {
        ObjectNode json = Json.object();
        json.put("type", block.type());
        switch (block) {
            case TextBlock text -> json.put("text", text.text());
            case ImageBlock image -> {
                putIf(json, "media_type", image.mediaType());
                putIf(json, "data", image.data());
                putIf(json, "url", image.url());
            }
            case ThinkingBlock thinking -> {
                putIf(json, "text", thinking.text());
                putIf(json, "signature", thinking.signature());
                if (thinking.redacted()) {
                    json.put("redacted", true);
                }
                putIf(json, "redacted_data", thinking.redactedData());
                putIf(json, "provider", thinking.provider());
            }
            case ToolCall call -> {
                json.put("id", call.id());
                json.put("name", call.name());
                json.set("arguments", Json.tree(call.arguments()));
                putIf(json, "raw_arguments", call.rawArguments());
                putIf(json, "parse_error", call.parseError());
            }
            case ToolResult result -> {
                json.put("tool_call_id", result.toolCallId());
                putIf(json, "tool_name", result.toolName());
                ArrayNode content = json.putArray("content");
                result.content().forEach(item -> content.add(block(item)));
                json.put("is_error", result.isError());
            }
        }
        return json;
    }

    public static ContentBlock block(JsonNode json) {
        String type = Json.text(json, "type");
        return switch (type == null ? "" : type) {
            case "text" -> new TextBlock(json.path("text").asText(""));
            case "image" -> new ImageBlock(Json.text(json, "media_type"), Json.text(json, "data"), Json.text(json, "url"));
            case "thinking" -> new ThinkingBlock(Json.text(json, "text"), Json.text(json, "signature"),
                    json.path("redacted").asBoolean(false), Json.text(json, "redacted_data"), Json.text(json, "provider"));
            case "tool_call" -> new ToolCall(json.path("id").asText(), json.path("name").asText(), Json.toMap(json.get("arguments")),
                    Json.text(json, "raw_arguments"), Json.text(json, "parse_error"));
            case "tool_result" -> {
                List<ContentBlock> content = new ArrayList<>();
                json.path("content").forEach(item -> content.add(block(item)));
                yield new ToolResult(json.path("tool_call_id").asText(), Json.text(json, "tool_name"), content,
                        json.path("is_error").asBoolean(false));
            }
            default -> throw new IllegalArgumentException("unknown block type: " + type);
        };
    }

    public static ObjectNode message(Message message) {
        ObjectNode json = Json.object();
        json.put("role", message.role().wire());
        ArrayNode content = json.putArray("content");
        message.content().forEach(part -> content.add(block(part)));
        if (message.providerPayload() != null) {
            ProviderPayload payload = message.providerPayload();
            ObjectNode raw = json.putObject("provider_raw");
            raw.put("provider", payload.provider());
            putIf(raw, "model", payload.model());
            raw.set("content", payload.rawContentJson() == null ? Json.array() : Json.read(payload.rawContentJson()));
        }
        return json;
    }

    public static Message message(JsonNode json) {
        Role role = Role.valueOf(json.path("role").asText("user").toUpperCase());
        List<ContentBlock> content = new ArrayList<>();
        json.path("content").forEach(item -> content.add(block(item)));
        ProviderPayload payload = null;
        JsonNode raw = json.get("provider_raw");
        if (raw != null && raw.isObject()) {
            payload = new ProviderPayload(raw.path("provider").asText(), Json.text(raw, "model"), Json.write(raw.path("content")));
        }
        return new Message(role, content, payload);
    }

    public static ObjectNode usage(TokenUsage usage) {
        ObjectNode json = Json.object();
        json.put("input_tokens", usage.inputTokens());
        json.put("output_tokens", usage.outputTokens());
        json.put("cache_read_tokens", usage.cacheReadTokens());
        json.put("cache_write_tokens", usage.cacheWriteTokens());
        json.put("requests", usage.requests());
        return json;
    }

    public static TokenUsage usage(JsonNode json) {
        if (json == null || json.isNull()) {
            return null;
        }
        return new TokenUsage(json.path("input_tokens").asLong(), json.path("output_tokens").asLong(),
                json.path("cache_read_tokens").asLong(), json.path("cache_write_tokens").asLong(), json.path("requests").asInt());
    }

    // ------------------------------------------------------------------ 领域事件（会话存储格式）

    public static ObjectNode event(DomainEvent event) {
        ObjectNode json = Json.object();
        json.put("ts", event.occurredAt().toString());
        switch (event) {
            case ConversationStarted started -> {
                json.put("t", "started");
                json.put("id", started.conversationId().value());
                json.set("metadata", Json.tree(started.metadata()));
            }
            case MessageAppended appended -> {
                json.put("t", "msg");
                json.set("m", message(appended.message()));
                if (appended.usage() != null) {
                    json.set("usage", usage(appended.usage()));
                }
            }
            case ConversationCompacted compacted -> {
                json.put("t", "compacted");
                json.put("summary", compacted.summary());
                ArrayNode messages = json.putArray("messages");
                compacted.messages().forEach(item -> messages.add(message(item)));
            }
            case MetadataUpdated updated -> {
                json.put("t", "meta");
                json.put("key", updated.key());
                json.put("value", updated.value());
            }
            case UsageRecorded recorded -> {
                json.put("t", "usage");
                json.set("usage", usage(recorded.usage()));
                json.put("reason", recorded.reason());
            }
            default -> throw new IllegalArgumentException("unsupported event " + event.getClass());
        }
        return json;
    }

    public static DomainEvent event(JsonNode json, ConversationId id) {
        Instant occurredAt = json.hasNonNull("ts") ? Instant.parse(json.get("ts").asText()) : Instant.now();
        return switch (json.path("t").asText()) {
            case "started" -> {
                Map<String, String> metadata = new LinkedHashMap<>();
                json.path("metadata").fields().forEachRemaining(field -> metadata.put(field.getKey(), field.getValue().asText()));
                yield new ConversationStarted(ConversationId.of(json.path("id").asText(id.value())), metadata, occurredAt);
            }
            case "msg" -> new MessageAppended(id, message(json.get("m")), usage(json.get("usage")), occurredAt);
            case "compacted" -> {
                List<Message> messages = new ArrayList<>();
                json.path("messages").forEach(item -> messages.add(message(item)));
                yield new ConversationCompacted(id, json.path("summary").asText(), messages, occurredAt);
            }
            case "usage" -> new UsageRecorded(id, usage(json.get("usage")), json.path("reason").asText(), occurredAt);
            case "meta" -> new MetadataUpdated(id, json.path("key").asText(), json.path("value").asText(), occurredAt);
            default -> null;
        };
    }

    private static void putIf(ObjectNode json, String key, String value) {
        if (value != null) {
            json.put(key, value);
        }
    }
}
