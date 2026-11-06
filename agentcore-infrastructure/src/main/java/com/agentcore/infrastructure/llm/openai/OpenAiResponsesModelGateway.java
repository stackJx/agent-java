package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.ProviderPayload;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ThinkingBlock;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String model;
    private final Options options;
    private final HttpClient http;
    private final URI endpoint;

    public OpenAiResponsesModelGateway(String model, Options options) {
        this.model = model;
        this.options = options;
        String baseUrl = options.baseUrl() == null ? DEFAULT_BASE_URL : options.baseUrl();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/responses");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public String provider() {
        return options.label() == null ? "openai" : options.label();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int contextWindow() {
        return options.contextWindow();
    }

    ObjectNode buildBody(ModelRequest request) {
        ObjectNode body = Json.object();
        body.put("model", model);
        if (!request.system().isEmpty()) {
            body.put("instructions", request.system());
        }
        body.set("input", OpenAiResponsesWire.toInput(request.messages()));
        body.put("max_output_tokens", request.maxTokens() != null ? request.maxTokens() : options.maxTokens());
        body.put("stream", true);
        body.put("store", options.store());
        if (!options.store()) {
            body.putArray("include").add("reasoning.encrypted_content"); // 无状态时取回加密推理，下一轮回放
        }
        if (!request.tools().isEmpty()) {
            body.set("tools", OpenAiResponsesWire.tools(request.tools()));
        }
        // 单次请求里的 effort 优先于配置
        Object requested = request.options().get("effort");
        String effort = requested != null ? String.valueOf(requested) : options.reasoningEffort();
        if (effort != null || options.reasoningSummary() != null) {
            ObjectNode reasoning = body.putObject("reasoning");
            if (effort != null) {
                reasoning.put("effort", effort);
            }
            if (options.reasoningSummary() != null) {
                reasoning.put("summary", options.reasoningSummary());
            }
        }
        if (options.extraBody() != null) {
            options.extraBody().forEach((key, value) -> body.set(key, Json.tree(value)));
        }
        request.options().forEach((key, value) -> {
            if (!key.equals("effort")) {
                body.set(key, Json.tree(value));
            }
        });
        return body;
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
        InputStream body = OpenAiHttp.post(http, endpoint, Json.write(buildBody(request)), options.apiKey(), options.headers(),
                options.timeout());
        StreamedResponse streamed = new StreamedResponse(listener);
        OpenAiHttp.readEvents(body, cancel, options.timeout(), streamed::accept);
        if (streamed.result == null) {
            throw new ModelException("stream ended before response.completed", true);
        }
        return toResponse(streamed.result, streamed.output());
    }

    /**
     * Responses 的流由一系列带 type 的事件组成。增量事件只用于实时回调；
     * 最终内容以 response.completed 里的完整 output 为准（个别兼容服务不带 output，就用逐个收到的 output_item.done）。
     */
    private static final class StreamedResponse {
        private final ModelStreamListener listener;
        private final Map<Integer, JsonNode> doneItems = new TreeMap<>();
        private JsonNode result;
        private String lastTextItem;

        StreamedResponse(ModelStreamListener listener) {
            this.listener = listener;
        }

        /** 处理一个事件；收到完成事件时返回 false，不再往下读。 */
        boolean accept(JsonNode event) {
            switch (event.path("type").asText()) {
                case "response.output_text.delta", "response.refusal.delta" -> {
                    String itemId = event.path("item_id").asText();
                    if (lastTextItem != null && !lastTextItem.equals(itemId)) {
                        listener.onText("\n\n"); // 多条 message（如 commentary + final_answer）之间空一行，与最终内容一致
                    }
                    lastTextItem = itemId;
                    listener.onText(event.path("delta").asText());
                }
                case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                        listener.onThinking(event.path("delta").asText());
                case "response.reasoning_summary_part.added" -> {
                    if (event.path("summary_index").asInt() > 0) {
                        listener.onThinking("\n\n");
                    }
                }
                case "response.output_item.added" -> {
                    JsonNode item = event.path("item");
                    if ("function_call".equals(item.path("type").asText())) {
                        listener.onToolCallStart(item.path("call_id").asText(), item.path("name").asText());
                    }
                }
                case "response.output_item.done" -> doneItems.put(event.path("output_index").asInt(doneItems.size()), event.path("item"));
                case "response.completed", "response.incomplete" -> {
                    result = event.path("response");
                    return false;
                }
                case "response.failed" -> throw failure(event.path("response").path("error"));
                case "error" -> throw failure(event);
                default -> {
                    // created / in_progress / content_part / 内置工具进度等：忽略
                }
            }
            return true;
        }

        /** 完成事件里的 output；有的兼容服务不带 output，就用逐个收到的 output_item.done。 */
        ArrayNode output() {
            ArrayNode output = Json.array();
            JsonNode completed = result.path("output");
            if (completed.isArray() && !completed.isEmpty()) {
                completed.forEach(output::add);
            } else {
                doneItems.values().forEach(output::add);
            }
            return output;
        }
    }

    /** output 条目 → 领域消息。原始 output 整体存进 ProviderPayload，下一轮原样回放。 */
    static ModelResponse toResponse(JsonNode result, ArrayNode output) {
        List<ContentBlock> content = new ArrayList<>();
        StringBuilder refusal = new StringBuilder();
        boolean hasText = false;
        boolean hasCalls = false;
        for (JsonNode item : output) {
            switch (item.path("type").asText()) {
                case "reasoning" -> {
                    String thinking = reasoningText(item);
                    if (!thinking.isEmpty()) {
                        content.add(ThinkingBlock.of(thinking, OpenAiResponsesWire.PROVIDER)); // 仅用于展示；回放靠原始条目
                    }
                }
                case "message" -> {
                    String text = messageText(item, refusal);
                    if (!text.isEmpty()) {
                        content.add(new TextBlock((hasText ? "\n\n" : "") + text));
                        hasText = true;
                    }
                }
                case "function_call" -> {
                    hasCalls = true;
                    content.add(OpenAiHttp.toolCall(item.hasNonNull("call_id") ? item.get("call_id").asText() : null,
                            item.path("name").asText(), item.path("arguments").asText("")));
                }
                default -> {
                    // 内置工具（web_search_call 等）：只保留在原始条目里
                }
            }
        }
        String model = result.path("model").asText(null);
        String status = result.path("status").asText("completed");
        String incompleteReason = result.path("incomplete_details").path("reason").asText(null);
        StopReason stop;
        String details = null;
        if ("incomplete".equals(status)) {
            stop = "content_filter".equals(incompleteReason) ? StopReason.REFUSAL : StopReason.MAX_TOKENS;
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
        String finishReason = incompleteReason == null ? status : status + ":" + incompleteReason;
        return new ModelResponse(message, stop, usageFrom(result.path("usage")), model, finishReason, details);
    }

    /** reasoning 条目：思考摘要和（若有）原文，段落之间空一行。 */
    private static String reasoningText(JsonNode item) {
        List<String> parts = new ArrayList<>();
        item.path("summary").forEach(summary -> parts.add(summary.path("text").asText()));
        item.path("content").forEach(part -> parts.add(part.path("text").asText()));
        return String.join("\n\n", parts.stream().filter(part -> !part.isEmpty()).toList());
    }

    /** message 条目：输出文字和拒答文字拼在一起；拒答文字同时记到 refusal 里。 */
    private static String messageText(JsonNode item, StringBuilder refusal) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : item.path("content")) {
            switch (part.path("type").asText()) {
                case "output_text" -> text.append(part.path("text").asText());
                case "refusal" -> {
                    refusal.append(part.path("refusal").asText());
                    text.append(part.path("refusal").asText());
                }
                default -> { }
            }
        }
        return text.toString();
    }

    /** input_tokens 含缓存命中与缓存写入部分；领域里三者分开记。 */
    static TokenUsage usageFrom(JsonNode usage) {
        long input = usage.path("input_tokens").asLong();
        long cached = usage.path("input_tokens_details").path("cached_tokens").asLong(0);
        long written = usage.path("input_tokens_details").path("cache_write_tokens").asLong(0);
        return new TokenUsage(Math.max(input - cached - written, 0), usage.path("output_tokens").asLong(), cached, written, 1);
    }

    /** 流里的错误事件 → 异常：超窗单独区分；没有错误码、服务端错误、限流、超时都可重试。 */
    static ModelException failure(JsonNode error) {
        String code = error.path("code").asText("");
        String message = "provider error" + (code.isEmpty() ? "" : " (" + code + ")") + ": " + error.path("message").asText("");
        String lower = (code + " " + error.path("message").asText("")).toLowerCase();
        if (lower.contains("context_length") || lower.contains("context window") || lower.contains("maximum context")) {
            return new ContextOverflowException(message, null);
        }
        boolean retryable = code.isEmpty() || lower.contains("server_error") || lower.contains("rate_limit")
                || lower.contains("overloaded") || lower.contains("timeout");
        return new ModelException(message, retryable);
    }
}
