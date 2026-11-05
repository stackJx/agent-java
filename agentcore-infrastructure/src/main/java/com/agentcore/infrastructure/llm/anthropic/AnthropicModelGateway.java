package com.agentcore.infrastructure.llm.anthropic;

import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.ProviderPayload;
import com.agentcore.domain.conversation.Role;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.model.ContextOverflowException;
import com.agentcore.domain.model.MalformedOutputException;
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
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Anthropic Claude 网关（官方 anthropic-java SDK，beta 命名空间）。
 *
 * <ul>
 *   <li>流式请求 + {@link BetaMessageAccumulator} 拼出完整消息，长输出不会触发 HTTP 超时；</li>
 *   <li>自适应思考（adaptive，显示摘要）+ output_config.effort；</li>
 *   <li>Prompt Caching：系统提示词显式断点 + 顶层自动缓存对话尾部；</li>
 *   <li>助手消息的原始内容块（toParam 后的 JSON）原样保存并回放：thinking 签名、fallback 标记都不丢；</li>
 *   <li>直连官方 API 时，对支持的模型默认开启服务端拒答回退（fallbacks: "default"）与工具参数流式。</li>
 * </ul>
 */
public final class AnthropicModelGateway implements ModelGateway {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    private static final Pattern ADAPTIVE = Pattern.compile("claude-(fable|mythos|opus-5|sonnet-5|haiku-5|opus-4-[678]|sonnet-4-6)");
    private static final Set<String> FALLBACK_MODELS = Set.of("claude-fable-5-1", "claude-mythos-5-1", "claude-opus-5-5",
            "claude-opus-5", "claude-sonnet-5-5");
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /** 网关配置。字段为 null 表示按模型与环境自动决定。 */
    public record Options(String apiKey, String baseUrl, Integer maxTokens, Integer contextWindow, Boolean thinking,
                          String thinkingDisplay, String effort, Boolean fallbacks, Boolean eagerInputStreaming,
                          Boolean promptCache, Integer maxRetries, Duration timeout, Map<String, Object> extraBody) {

        public static Options defaults() {
            return new Options(null, null, null, null, null, null, null, null, null, null, null, null, Map.of());
        }
    }

    private final AnthropicClient client;
    private final String model;
    private final int maxTokens;
    private final int contextWindow;
    private final boolean thinking;
    private final String thinkingDisplay;
    private final String effort;
    private final boolean fallbacks;
    private final boolean eagerInputStreaming;
    private final boolean promptCache;
    private final Map<String, Object> extraBody;
    private final ObjectMapper sdkMapper = ObjectMappers.jsonMapper();

    public AnthropicModelGateway(String model, Options o) {
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        Options opt = o == null ? Options.defaults() : o;
        boolean adaptive = ADAPTIVE.matcher(this.model).find();
        boolean firstParty = opt.baseUrl() == null && System.getenv("ANTHROPIC_BASE_URL") == null;
        this.maxTokens = opt.maxTokens() != null ? opt.maxTokens() : 64_000;
        this.contextWindow = opt.contextWindow() != null ? opt.contextWindow() : adaptive ? 1_000_000 : 200_000;
        this.thinking = opt.thinking() != null ? opt.thinking() : adaptive;
        this.thinkingDisplay = opt.thinkingDisplay() != null ? opt.thinkingDisplay() : "summarized";
        this.effort = opt.effort() != null ? (opt.effort().isBlank() ? null : opt.effort()) : (adaptive ? "high" : null);
        this.fallbacks = opt.fallbacks() != null ? opt.fallbacks() : firstParty && FALLBACK_MODELS.contains(this.model);
        this.eagerInputStreaming = opt.eagerInputStreaming() != null ? opt.eagerInputStreaming() : firstParty;
        this.promptCache = opt.promptCache() == null || opt.promptCache();
        this.extraBody = opt.extraBody() == null ? Map.of() : opt.extraBody();

        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().fromEnv();
        if (opt.apiKey() != null && !opt.apiKey().isBlank()) {
            b.apiKey(opt.apiKey());
        }
        if (opt.baseUrl() != null) {
            b.baseUrl(opt.baseUrl());
        }
        b.maxRetries(opt.maxRetries() != null ? opt.maxRetries() : 3);
        b.timeout(opt.timeout() != null ? opt.timeout() : Duration.ofMinutes(10));
        this.client = b.build();
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int contextWindow() {
        return contextWindow;
    }

    public boolean fallbacksEnabled() {
        return fallbacks;
    }

    // ------------------------------------------------------------------ 请求

    MessageCreateParams buildParams(ModelRequest request) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens() != null ? request.maxTokens() : maxTokens);
        if (!request.system().isEmpty()) {
            BetaTextBlockParam.Builder sys = BetaTextBlockParam.builder().text(request.system());
            if (promptCache) {
                sys.cacheControl(BetaCacheControlEphemeral.builder().build());
            }
            b.systemOfBetaTextBlockParams(List.of(sys.build()));
        }
        if (promptCache) {
            b.cacheControl(BetaCacheControlEphemeral.builder().build());
        }
        for (AnthropicWire.WireMessage wm : AnthropicWire.toWire(request.messages())) {
            List<BetaContentBlockParam> blocks = new ArrayList<>();
            for (JsonNode n : wm.content()) {
                blocks.add(sdkMapper.convertValue(n, BetaContentBlockParam.class));
            }
            b.addMessage(wm.role() == Role.USER ? BetaMessageParam.ofUserOfContentBlockParams(blocks)
                    : BetaMessageParam.ofAssistantOfContentBlockParams(blocks));
        }
        for (ToolSpec t : request.tools()) {
            BetaTool.Builder tb = BetaTool.builder().name(t.name()).description(t.description())
                    .inputSchema(sdkMapper.convertValue(t.inputSchema(), BetaTool.InputSchema.class));
            if (eagerInputStreaming) {
                tb.eagerInputStreaming(true);
            }
            b.addTool(tb.build());
        }
        if (thinking) {
            b.thinking(BetaThinkingConfigAdaptive.builder().display(BetaThinkingConfigAdaptive.Display.of(thinkingDisplay)).build());
        }
        String eff = request.options().get("effort") instanceof String s ? s : effort;
        if (eff != null) {
            b.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(eff)).build());
        }
        if (fallbacks) {
            b.fallbacksDefault();
            b.addBeta(FALLBACK_BETA);
        }
        extraBody.forEach((k, v) -> b.putAdditionalBodyProperty(k, JsonValue.from(v)));
        return b.build();
    }

    // ------------------------------------------------------------------ 流式调用

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
        MessageCreateParams params = buildParams(request);
        BetaMessageAccumulator acc = BetaMessageAccumulator.create();
        try (StreamResponse<BetaRawMessageStreamEvent> sr = client.beta().messages().createStreaming(params)) {
            Runnable unregister = cancel.onCancel(sr::close);
            try {
                for (BetaRawMessageStreamEvent ev : sr) {
                    acc.accumulate(ev);
                    ev.contentBlockStart().ifPresent(s -> s.contentBlock().toolUse()
                            .ifPresent(tu -> listener.onToolCallStart(tu.id(), tu.name())));
                    ev.contentBlockDelta().ifPresent(d -> {
                        d.delta().text().ifPresent(t -> listener.onText(t.text()));
                        d.delta().thinking().ifPresent(t -> listener.onThinking(t.thinking()));
                    });
                }
            } finally {
                unregister.run();
            }
        } catch (RuntimeException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw translate(e);
        }
        if (cancel.isCancelled()) {
            throw new CancelledException();
        }
        return toResponse(acc.message());
    }

    private ModelResponse toResponse(BetaMessage msg) {
        List<JsonNode> raw = new ArrayList<>();
        for (BetaContentBlock block : msg.content()) {
            raw.add(rawBlock(block));
        }
        raw = AnthropicWire.applyFallbackEchoRule(raw);
        List<ContentBlock> content = AnthropicWire.fromWire(raw);
        TokenUsage usage = new TokenUsage(msg.usage().inputTokens(), msg.usage().outputTokens(),
                msg.usage().cacheReadInputTokens().orElse(0L), msg.usage().cacheCreationInputTokens().orElse(0L), 1);
        String rawStop = msg.stopReason().map(r -> r.asString()).orElse(null);
        StopReason stop = mapStop(rawStop);
        if (stop == StopReason.END_TURN && content.stream().anyMatch(ToolCall.class::isInstance)) {
            stop = StopReason.TOOL_USE;
        }
        String details = msg.stopDetails().map(d -> {
            try {
                return sdkMapper.writeValueAsString(d);
            } catch (Exception e) {
                return String.valueOf(d);
            }
        }).orElse(null);
        String modelName = msg.model().asString();
        Message m = Message.assistant(content, new ProviderPayload(AnthropicWire.PROVIDER, modelName, Json.write(raw)));
        return new ModelResponse(m, stop, usage, modelName, rawStop, details);
    }

    /** 优先用 toParam()（请求格式，可直接回放）；不支持的块类型退回到响应 JSON。 */
    private JsonNode rawBlock(BetaContentBlock block) {
        try {
            return Json.read(sdkMapper.writeValueAsString(block.toParam()));
        } catch (Exception e) {
            try {
                return Json.read(sdkMapper.writeValueAsString(block));
            } catch (Exception e2) {
                return Json.object().put("type", "unknown");
            }
        }
    }

    static StopReason mapStop(String raw) {
        if (raw == null) {
            return StopReason.END_TURN;
        }
        return switch (raw) {
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "pause_turn" -> StopReason.PAUSE_TURN;
            case "refusal" -> StopReason.REFUSAL;
            case "model_context_window_exceeded" -> StopReason.CONTEXT_OVERFLOW;
            default -> StopReason.END_TURN;
        };
    }

    private static ModelException translate(RuntimeException e) {
        if (e instanceof ModelException me) {
            return me;
        }
        if (e instanceof AnthropicInvalidDataException) {
            // eager_input_streaming 下无法解析的工具参数：没有完整的 tool_use 可回复，只能重发本轮
            return new MalformedOutputException("unparseable model output: " + e.getMessage(), e);
        }
        if (e instanceof BadRequestException br) {
            String msg = String.valueOf(br.getMessage());
            String low = msg.toLowerCase();
            if (low.contains("prompt is too long") || low.contains("context window") || low.contains("too many tokens")) {
                return new ContextOverflowException(msg, e);
            }
            return new ModelException(msg, false, 400, e);
        }
        if (e instanceof RateLimitException || e instanceof InternalServerException || e instanceof AnthropicIoException
                || e instanceof AnthropicRetryableException) {
            return new ModelException(e.getMessage(), true, e instanceof AnthropicServiceException s ? s.statusCode() : null, e);
        }
        if (e instanceof AnthropicServiceException s) {
            return new ModelException(s.getMessage(), s.statusCode() >= 500, s.statusCode(), e);
        }
        return new ModelException(e.getClass().getSimpleName() + ": " + e.getMessage(), false, null, e);
    }

    @Override
    public void close() {
        client.close();
    }
}
