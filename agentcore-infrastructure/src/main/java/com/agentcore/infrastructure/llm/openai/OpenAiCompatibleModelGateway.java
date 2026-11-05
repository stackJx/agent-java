package com.agentcore.infrastructure.llm.openai;

import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ThinkingBlock;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.model.ModelException;
import com.agentcore.domain.model.ModelGateway;
import com.agentcore.domain.model.ModelRequest;
import com.agentcore.domain.model.ModelResponse;
import com.agentcore.domain.model.ModelStreamListener;
import com.agentcore.domain.model.StopReason;
import com.agentcore.domain.model.ToolSpec;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private final String model;
    private final Options o;
    private final HttpClient http;
    private final URI endpoint;

    public OpenAiCompatibleModelGateway(String model, Options options) {
        this.model = model;
        this.o = options;
        String base = options.baseUrl() == null ? "https://api.openai.com/v1" : options.baseUrl();
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
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
        body.set("messages", OpenAiWire.toWire(request.system(), request.messages(), o.echoReasoning()));
        body.put(o.maxTokensParam() == null ? "max_tokens" : o.maxTokensParam(),
                request.maxTokens() != null ? request.maxTokens() : o.maxTokens());
        body.put("stream", true);
        if (o.streamUsage()) {
            body.putObject("stream_options").put("include_usage", true);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec t : request.tools()) {
                ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
                fn.put("name", t.name()).put("description", t.description());
                fn.set("parameters", Json.tree(t.inputSchema()));
            }
        }
        if (o.reasoningEffort() != null) {
            body.put("reasoning_effort", o.reasoningEffort());
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
        InputStream in = OpenAiHttp.post(http, endpoint, Json.write(buildBody(request)), o.apiKey(), o.headers(), o.timeout());

        // 流式响应是一串 "data: {chunk}" 行，每个 chunk 只带一点增量（delta）。
        // 文字、思考直接拼接；工具调用按 index 分别累加（id、name 只在第一块出现，arguments 分多块到达）
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, Acc> calls = new TreeMap<>();
        String finish = null;
        TokenUsage usage = new TokenUsage(0, 0, 0, 0, 1);
        String modelName = model;
        Runnable unregister = OpenAiHttp.closeOnCancel(cancel, in);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) {
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }
                JsonNode chunk = Json.read(data);
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
                    continue;
                }
                JsonNode ch = choices.get(0);
                JsonNode delta = ch.path("delta");
                String rc = delta.hasNonNull("reasoning_content") ? delta.get("reasoning_content").asText()
                        : delta.hasNonNull("reasoning") && delta.get("reasoning").isTextual() ? delta.get("reasoning").asText() : null;
                if (rc != null && !rc.isEmpty()) {
                    reasoning.append(rc);
                    listener.onThinking(rc);
                }
                if (delta.hasNonNull("content") && delta.get("content").isTextual()) {
                    String c = delta.get("content").asText();
                    if (!c.isEmpty()) {
                        text.append(c);
                        listener.onText(c);
                    }
                }
                JsonNode tcs = delta.path("tool_calls");
                if (tcs.isArray()) {
                    for (int pos = 0; pos < tcs.size(); pos++) {
                        JsonNode tc = tcs.get(pos);
                        int idx = tc.hasNonNull("index") ? tc.get("index").asInt() : pos;
                        Acc acc = calls.computeIfAbsent(idx, k -> new Acc());
                        if (tc.hasNonNull("id")) {
                            acc.id = tc.get("id").asText();
                        }
                        JsonNode fn = tc.path("function");
                        if (fn.hasNonNull("name") && acc.name.isEmpty()) {
                            acc.name = fn.get("name").asText();
                        }
                        if (!acc.started && !acc.name.isEmpty()) {
                            if (acc.id == null) {
                                acc.id = OpenAiHttp.newCallId();
                            }
                            acc.started = true;
                            listener.onToolCallStart(acc.id, acc.name);
                        }
                        if (fn.hasNonNull("arguments")) {
                            acc.args.append(fn.get("arguments").asText());
                        }
                    }
                }
                if (ch.hasNonNull("finish_reason")) {
                    finish = ch.get("finish_reason").asText();
                }
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new ModelException("stream interrupted: " + e.getMessage(), true, null, e);
        } catch (java.io.UncheckedIOException e) {
            throw new ModelException("invalid stream chunk: " + e.getMessage(), true, null, e);
        } finally {
            unregister.run();
        }
        if (cancel.isCancelled()) {
            throw new CancelledException();
        }
        if ("insufficient_system_resource".equals(finish)) {
            throw new ModelException("provider reported insufficient_system_resource", true);
        }

        // 流读完了，拼出完整的助手消息：思考 → 文字 → 工具调用。参数解析失败不抛异常，记在 parseError 里交给引擎回报给模型
        List<ContentBlock> content = new ArrayList<>();
        if (!reasoning.isEmpty()) {
            content.add(ThinkingBlock.of(reasoning.toString(), OpenAiWire.PROVIDER));
        }
        if (!text.isEmpty()) {
            content.add(new TextBlock(text.toString()));
        }
        for (Acc acc : calls.values()) {
            content.add(OpenAiHttp.toolCall(acc.id, acc.name, acc.args.toString()));
        }
        StopReason stop = switch (finish == null ? "stop" : finish) {
            case "tool_calls", "function_call" -> StopReason.TOOL_USE;
            case "length" -> StopReason.MAX_TOKENS;
            case "content_filter" -> StopReason.REFUSAL;
            default -> StopReason.END_TURN;
        };
        if (!calls.isEmpty() && stop == StopReason.END_TURN) {
            stop = StopReason.TOOL_USE;
        }
        return new ModelResponse(Message.assistant(content, null), stop, usage, modelName, finish, null);
    }

    private static final class Acc {
        String id;
        String name = "";
        boolean started;
        final StringBuilder args = new StringBuilder();
    }

    static TokenUsage usageFrom(JsonNode u) {
        long prompt = u.path("prompt_tokens").asLong();
        long cached = u.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        if (cached == 0) {
            cached = u.path("prompt_cache_hit_tokens").asLong(0); // DeepSeek
        }
        return new TokenUsage(Math.max(prompt - cached, 0), u.path("completion_tokens").asLong(), cached, 0, 1);
    }
}
