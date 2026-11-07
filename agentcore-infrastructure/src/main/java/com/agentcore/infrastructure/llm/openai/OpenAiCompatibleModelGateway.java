package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ThinkingBlock;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.llm.model.ToolSpec;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OpenAI Chat Completions 兼容网关：DeepSeek、通义千问、Kimi、智谱 GLM、OpenRouter、OpenAI，
 * 以及本地 vLLM / Ollama / LM Studio 等。
 *
 * <p>直接基于 JDK HttpClient + SSE 解析实现，便于处理各家的扩展字段（reasoning_content、
 * prompt_cache_hit_tokens 等），不引入额外依赖。
 */
public final class OpenAiCompatibleModelGateway implements ModelGateway {

    /**
     * @param label          provider 标签（deepseek / qwen / openai ...），仅用于展示
     * @param maxTokensParam 有的服务要求 max_completion_tokens（OpenAI 新模型）
     * @param echoReasoning  回传历史 reasoning_content（DeepSeek 思考模式 + tools 时必须）
     */
    public record Options(String label, String baseUrl, String apiKey, int maxTokens, String maxTokensParam,
                          int contextWindow, String reasoningEffort, boolean echoReasoning, boolean streamUsage,
                          Duration timeout, Map<String, String> headers, Map<String, Object> extraBody) {
    }

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String model;
    private final Options options;
    private final HttpClient http;
    private final URI endpoint;

    public OpenAiCompatibleModelGateway(String model, Options options) {
        this.model = model;
        this.options = options;
        String baseUrl = options.baseUrl() == null ? DEFAULT_BASE_URL : options.baseUrl();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
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

    /** 动态配置按运行创建网关；结束时立即释放连接池，不依赖 GC 回收客户端。 */
    @Override
    public void close() {
        http.shutdownNow();
    }

    ObjectNode buildBody(ModelRequest request) {
        ObjectNode body = Json.object();
        body.put("model", model);
        body.set("messages", OpenAiWire.toWire(request.system(), request.messages(), options.echoReasoning()));
        body.put(options.maxTokensParam() == null ? "max_tokens" : options.maxTokensParam(),
                request.maxTokens() != null ? request.maxTokens() : options.maxTokens());
        body.put("stream", true);
        if (options.streamUsage()) {
            body.putObject("stream_options").put("include_usage", true);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec tool : request.tools()) {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name()).put("description", tool.description());
                function.set("parameters", Json.tree(tool.inputSchema()));
            }
        }
        if (options.reasoningEffort() != null) {
            body.put("reasoning_effort", options.reasoningEffort());
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
        StreamedReply reply = new StreamedReply(model, listener);
        boolean finished = OpenAiHttp.readEvents(body, cancel, options.timeout(), chunk -> {
            reply.accept(chunk);
            return true;
        });
        // 既没收到 [DONE] 也没收到 finish_reason 就断了（例如代理断开）：回复不完整，不能当作正常结束存下来
        if (!finished && reply.finishReason == null) {
            throw new ModelException("stream ended before the response was complete", true);
        }
        if ("insufficient_system_resource".equals(reply.finishReason)) {
            throw new ModelException("provider reported insufficient_system_resource", true);
        }
        return reply.toResponse();
    }

    /**
     * 把流式 chunk 累加成一条完整回复。每个 chunk 只带一点增量（delta）：
     * 文字、思考直接拼接并实时回调；工具调用按 index 分别累加（id、name 只在第一块出现，arguments 分多块到达）。
     */
    private static final class StreamedReply {
        private final ModelStreamListener listener;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, PartialToolCall> toolCalls = new TreeMap<>();
        private String modelName;
        private String finishReason;
        private TokenUsage usage = new TokenUsage(0, 0, 0, 0, 1);

        StreamedReply(String model, ModelStreamListener listener) {
            this.modelName = model;
            this.listener = listener;
        }

        void accept(JsonNode chunk) {
            if (chunk.hasNonNull("error")) {
                throw new ModelException("provider error: " + chunk.get("error"), true, null, null);
            }
            if (chunk.hasNonNull("model")) {
                modelName = chunk.get("model").asText();
            }
            if (chunk.hasNonNull("usage")) {
                usage = usageFrom(chunk.get("usage"));
            }
            JsonNode choices = chunk.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            appendReasoning(delta);
            appendText(delta);
            appendToolCalls(delta.path("tool_calls"));
            if (choice.hasNonNull("finish_reason")) {
                finishReason = choice.get("finish_reason").asText();
            }
        }

        /** 思考内容：DeepSeek 等用 reasoning_content，OpenRouter 等用 reasoning。 */
        private void appendReasoning(JsonNode delta) {
            String piece = delta.hasNonNull("reasoning_content") ? delta.get("reasoning_content").asText()
                    : delta.hasNonNull("reasoning") && delta.get("reasoning").isTextual() ? delta.get("reasoning").asText() : null;
            if (piece != null && !piece.isEmpty()) {
                reasoning.append(piece);
                listener.onThinking(piece);
            }
        }

        private void appendText(JsonNode delta) {
            if (delta.hasNonNull("content") && delta.get("content").isTextual()) {
                String piece = delta.get("content").asText();
                if (!piece.isEmpty()) {
                    text.append(piece);
                    listener.onText(piece);
                }
            }
        }

        private void appendToolCalls(JsonNode deltas) {
            if (!deltas.isArray()) {
                return;
            }
            for (int position = 0; position < deltas.size(); position++) {
                JsonNode delta = deltas.get(position);
                int index = delta.hasNonNull("index") ? delta.get("index").asInt() : position;
                PartialToolCall call = toolCalls.computeIfAbsent(index, key -> new PartialToolCall());
                if (delta.hasNonNull("id") && !delta.get("id").asText().isBlank()) {
                    call.id = delta.get("id").asText();
                }
                JsonNode function = delta.path("function");
                if (function.hasNonNull("name") && call.name.isEmpty()) {
                    call.name = function.get("name").asText();
                }
                // 名字一到就通知界面"开始生成某个工具调用"，只通知一次
                if (!call.announced && !call.name.isEmpty()) {
                    if (call.id == null) {
                        call.id = OpenAiHttp.newCallId();
                    }
                    call.announced = true;
                    listener.onToolCallStart(call.id, call.name);
                }
                if (function.hasNonNull("arguments")) {
                    call.arguments.append(function.get("arguments").asText());
                }
            }
        }

        /** 拼出完整的助手消息：思考 → 文字 → 工具调用。参数解析失败不抛异常，记在 parseError 里交给引擎回报给模型。 */
        ModelResponse toResponse() {
            List<ContentBlock> content = new ArrayList<>();
            if (!reasoning.isEmpty()) {
                content.add(ThinkingBlock.of(reasoning.toString(), OpenAiWire.PROVIDER));
            }
            if (!text.isEmpty()) {
                content.add(new TextBlock(text.toString()));
            }
            Set<String> usedIds = new HashSet<>();
            for (PartialToolCall call : toolCalls.values()) {
                content.add(OpenAiHttp.toolCall(uniqueId(call.id, usedIds), call.name, call.arguments.toString()));
            }
            return new ModelResponse(Message.assistant(content, null), stopReason(), usage, modelName, finishReason, null);
        }

        /**
         * 工具调用 ID 必须互不相同：结果按 ID 回填。有的兼容服务给空 ID 或重复 ID，这时生成一个新的
         * （Chat Completions 的历史由我们自己回放，换掉 ID 不会和服务端对不上）。
         */
        private static String uniqueId(String id, Set<String> usedIds) {
            String unique = id == null || id.isBlank() || usedIds.contains(id) ? OpenAiHttp.newCallId() : id;
            usedIds.add(unique);
            return unique;
        }

        private StopReason stopReason() {
            StopReason stop = switch (finishReason == null ? "stop" : finishReason) {
                case "tool_calls", "function_call" -> StopReason.TOOL_USE;
                case "length" -> StopReason.MAX_TOKENS;
                case "content_filter" -> StopReason.REFUSAL;
                default -> StopReason.END_TURN;
            };
            // 有的服务带了工具调用，finish_reason 却是 stop
            return !toolCalls.isEmpty() && stop == StopReason.END_TURN ? StopReason.TOOL_USE : stop;
        }
    }

    /** 正在流式到达的一个工具调用。 */
    private static final class PartialToolCall {
        String id;
        String name = "";
        boolean announced;
        final StringBuilder arguments = new StringBuilder();
    }

    static TokenUsage usageFrom(JsonNode usage) {
        long prompt = usage.path("prompt_tokens").asLong();
        long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        if (cached == 0) {
            cached = usage.path("prompt_cache_hit_tokens").asLong(0); // DeepSeek
        }
        return new TokenUsage(Math.max(prompt - cached, 0), usage.path("completion_tokens").asLong(), cached, 0, 1);
    }
}
