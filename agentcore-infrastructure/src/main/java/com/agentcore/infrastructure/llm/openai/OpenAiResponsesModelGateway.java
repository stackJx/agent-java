package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.ProviderPayload;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ThinkingBlock;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.model.ContextOverflowException;
import com.agentcore.domain.model.ModelException;
import com.agentcore.domain.model.ModelGateway;
import com.agentcore.domain.model.ModelRequest;
import com.agentcore.domain.model.ModelResponse;
import com.agentcore.domain.model.ModelStreamListener;
import com.agentcore.domain.model.StopReason;
import com.agentcore.domain.shared.CancellationToken;
import com.agentcore.domain.shared.CancelledException;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * OpenAI Responses API 网关（{@code POST /responses}）。OpenAI 新模型的推荐协议，
 * 函数调用、推理延续都依赖它；也适用于兼容 Responses 协议的其他服务。
 *
 * <p>默认无状态（{@code store: false}）：每次发送完整历史，上一轮的 output 条目（含加密推理）
 * 原样回放，会话数据只在本地仓储里。
 */
public final class OpenAiResponsesModelGateway implements ModelGateway {

    /**
     * @param reasoningEffort  reasoning.effort：none / minimal / low / medium / high / xhigh / max（取决于模型）
     * @param reasoningSummary reasoning.summary：auto / concise / detailed；开启后能看到思考摘要（部分组织需先完成验证）
     * @param store            是否让服务端保存响应；默认 false
     */
    public record Options(String label, String baseUrl, String apiKey, int maxTokens, int contextWindow,
                          String reasoningEffort, String reasoningSummary, boolean store,
                          Duration timeout, Map<String, String> headers, Map<String, Object> extraBody) {
    }

    private final String model;
    private final Options o;
    private final HttpClient http;
    private final URI endpoint;

    public OpenAiResponsesModelGateway(String model, Options options) {
        this.model = model;
        this.o = options;
        String base = options.baseUrl() == null ? "https://api.openai.com/v1" : options.baseUrl();
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/responses");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public String provider() {
        return o.label() == null ? "openai" : o.label();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int contextWindow() {
        return o.contextWindow();
    }

    ObjectNode buildBody(ModelRequest request) {
        ObjectNode body = Json.object();
        body.put("model", model);
        if (!request.system().isEmpty()) {
            body.put("instructions", request.system());
        }
        body.set("input", OpenAiResponsesWire.toInput(request.messages()));
        body.put("max_output_tokens", request.maxTokens() != null ? request.maxTokens() : o.maxTokens());
        body.put("stream", true);
        body.put("store", o.store());
        if (!o.store()) {
            body.putArray("include").add("reasoning.encrypted_content"); // 无状态时取回加密推理，下一轮回放
        }
        if (!request.tools().isEmpty()) {
            body.set("tools", OpenAiResponsesWire.tools(request.tools()));
        }
        Object effort = request.options().get("effort");
        String eff = effort != null ? String.valueOf(effort) : o.reasoningEffort();
        if (eff != null || o.reasoningSummary() != null) {
            ObjectNode r = body.putObject("reasoning");
            if (eff != null) {
                r.put("effort", eff);
            }
            if (o.reasoningSummary() != null) {
                r.put("summary", o.reasoningSummary());
            }
        }
        if (o.extraBody() != null) {
            o.extraBody().forEach((k, v) -> body.set(k, Json.tree(v)));
        }
        request.options().forEach((k, v) -> {
            if (!k.equals("effort")) {
                body.set(k, Json.tree(v));
            }
        });
        return body;
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .timeout(o.timeout() == null ? Duration.ofMinutes(10) : o.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(buildBody(request))));
        if (o.apiKey() != null && !o.apiKey().isBlank()) {
            rb.header("Authorization", "Bearer " + o.apiKey());
        }
        if (o.headers() != null) {
            o.headers().forEach(rb::header);
        }
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ModelException("connection failed: " + e.getMessage(), true, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        if (resp.statusCode() >= 400) {
            String body;
            try (InputStream in = resp.body()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                body = "";
            }
            throw OpenAiCompatibleModelGateway.httpError(resp.statusCode(), body);
        }

        Map<Integer, JsonNode> doneItems = new TreeMap<>();
        JsonNode result = null;
        String lastTextItem = null;
        InputStream in = resp.body();
        Runnable unregister = cancel.onCancel(() -> {
            try {
                in.close();
            } catch (IOException ignored) {
                // 关闭以中断阻塞读
            }
        });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            loop:
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    continue;
                }
                JsonNode ev = Json.read(data);
                switch (ev.path("type").asText()) {
                    case "response.output_text.delta", "response.refusal.delta" -> {
                        String itemId = ev.path("item_id").asText();
                        if (lastTextItem != null && !lastTextItem.equals(itemId)) {
                            listener.onText("\n\n"); // 多条 message（如 commentary + final_answer）之间空一行，与最终内容一致
                        }
                        lastTextItem = itemId;
                        listener.onText(ev.path("delta").asText());
                    }
                    case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                            listener.onThinking(ev.path("delta").asText());
                    case "response.reasoning_summary_part.added" -> {
                        if (ev.path("summary_index").asInt() > 0) {
                            listener.onThinking("\n\n");
                        }
                    }
                    case "response.output_item.added" -> {
                        JsonNode item = ev.path("item");
                        if ("function_call".equals(item.path("type").asText())) {
                            listener.onToolCallStart(item.path("call_id").asText(), item.path("name").asText());
                        }
                    }
                    case "response.output_item.done" -> doneItems.put(ev.path("output_index").asInt(doneItems.size()), ev.path("item"));
                    case "response.completed", "response.incomplete" -> {
                        result = ev.path("response");
                        break loop;
                    }
                    case "response.failed" -> throw failure(ev.path("response").path("error"));
                    case "error" -> throw failure(ev);
                    default -> {
                        // created / in_progress / content_part / 内置工具进度等：忽略
                    }
                }
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new ModelException("stream interrupted: " + e.getMessage(), true, null, e);
        } catch (java.io.UncheckedIOException e) {
            throw new ModelException("invalid stream event: " + e.getMessage(), true, null, e);
        } finally {
            unregister.run();
        }
        if (cancel.isCancelled()) {
            throw new CancelledException();
        }
        if (result == null) {
            throw new ModelException("stream ended before response.completed", true);
        }
        ArrayNode output = Json.array();
        if (result.path("output").isArray() && !result.path("output").isEmpty()) {
            result.path("output").forEach(output::add);
        } else {
            doneItems.values().forEach(output::add); // 有的兼容服务在完成事件里不带 output
        }
        return toResponse(result, output);
    }

    static ModelResponse toResponse(JsonNode result, ArrayNode output) {
        List<ContentBlock> content = new ArrayList<>();
        StringBuilder refusal = new StringBuilder();
        boolean hasText = false;
        boolean hasCalls = false;
        for (JsonNode item : output) {
            switch (item.path("type").asText()) {
                case "reasoning" -> {
                    List<String> parts = new ArrayList<>();
                    item.path("summary").forEach(s -> parts.add(s.path("text").asText()));
                    item.path("content").forEach(c -> parts.add(c.path("text").asText()));
                    String text = String.join("\n\n", parts.stream().filter(s -> !s.isEmpty()).toList());
                    if (!text.isEmpty()) {
                        content.add(ThinkingBlock.of(text, OpenAiResponsesWire.PROVIDER)); // 仅用于展示；回放靠原始条目
                    }
                }
                case "message" -> {
                    StringBuilder text = new StringBuilder();
                    for (JsonNode c : item.path("content")) {
                        switch (c.path("type").asText()) {
                            case "output_text" -> text.append(c.path("text").asText());
                            case "refusal" -> {
                                refusal.append(c.path("refusal").asText());
                                text.append(c.path("refusal").asText());
                            }
                            default -> { }
                        }
                    }
                    if (!text.isEmpty()) {
                        content.add(new TextBlock((hasText ? "\n\n" : "") + text));
                        hasText = true;
                    }
                }
                case "function_call" -> {
                    hasCalls = true;
                    content.add(toolCall(item));
                }
                default -> {
                    // 内置工具（web_search_call 等）：只保留在原始条目里
                }
            }
        }
        String model = result.path("model").asText(null);
        String status = result.path("status").asText("completed");
        String reason = result.path("incomplete_details").path("reason").asText(null);
        StopReason stop;
        String details = null;
        if ("incomplete".equals(status)) {
            stop = "content_filter".equals(reason) ? StopReason.REFUSAL : StopReason.MAX_TOKENS;
            details = Json.write(result.path("incomplete_details"));
        } else if (hasCalls) {
            stop = StopReason.TOOL_USE;
        } else if (!refusal.isEmpty()) {
            stop = StopReason.REFUSAL;
            details = Json.write(Json.object().put("refusal", refusal.toString()));
        } else {
            stop = StopReason.END_TURN;
        }
        Message message = Message.assistant(content, new ProviderPayload(OpenAiResponsesWire.PROVIDER, model, Json.write(output)));
        return new ModelResponse(message, stop, usageFrom(result.path("usage")), model,
                reason == null ? status : status + ":" + reason, details);
    }

    private static ToolCall toolCall(JsonNode item) {
        String raw = item.path("arguments").asText("");
        String id = item.hasNonNull("call_id") ? item.get("call_id").asText()
                : "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        Map<String, Object> args = new LinkedHashMap<>();
        String parseError = null;
        try {
            JsonNode parsed = raw.isBlank() ? Json.object() : Json.read(raw);
            if (parsed.isObject()) {
                args = Json.toMap(parsed);
            } else {
                parseError = "tool arguments must be a JSON object, got: " + clip(raw);
            }
        } catch (RuntimeException e) {
            parseError = "invalid JSON arguments: " + clip(raw);
        }
        return new ToolCall(id, item.path("name").asText(), args, raw, parseError);
    }

    /** input_tokens 含缓存命中与缓存写入部分；领域里三者分开记。 */
    static TokenUsage usageFrom(JsonNode u) {
        long input = u.path("input_tokens").asLong();
        long cached = u.path("input_tokens_details").path("cached_tokens").asLong(0);
        long written = u.path("input_tokens_details").path("cache_write_tokens").asLong(0);
        return new TokenUsage(Math.max(input - cached - written, 0), u.path("output_tokens").asLong(), cached, written, 1);
    }

    static ModelException failure(JsonNode error) {
        String code = error.path("code").asText("");
        String msg = "provider error" + (code.isEmpty() ? "" : " (" + code + ")") + ": " + error.path("message").asText("");
        String low = (code + " " + error.path("message").asText("")).toLowerCase();
        if (low.contains("context_length") || low.contains("context window") || low.contains("maximum context")) {
            return new ContextOverflowException(msg, null);
        }
        boolean retryable = code.isEmpty() || low.contains("server_error") || low.contains("rate_limit")
                || low.contains("overloaded") || low.contains("timeout");
        return new ModelException(msg, retryable);
    }

    private static String clip(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
