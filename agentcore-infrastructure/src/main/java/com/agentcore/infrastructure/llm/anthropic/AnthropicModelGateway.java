package com.agentcore.infrastructure.llm.anthropic;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.ProviderPayload;
import com.agentcore.domain.conversation.message.Role;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.MalformedOutputException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.llm.model.ToolSpec;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
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

    private static final int DEFAULT_MAX_TOKENS = 64_000;
    private static final int ADAPTIVE_CONTEXT_WINDOW = 1_000_000;
    private static final int LEGACY_CONTEXT_WINDOW = 200_000;
    /** SDK 自己的 HTTP 重试次数（引擎外面还有一层 RetryingModelGateway）。 */
    private static final int DEFAULT_SDK_RETRIES = 3;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

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

    /** options 里为 null 的字段按模型自动决定：新模型（自适应思考）默认开思考、effort=high、1M 上下文。 */
    public AnthropicModelGateway(String model, Options options) {
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        Options given = options == null ? Options.defaults() : options;
        boolean adaptive = ADAPTIVE.matcher(this.model).find();
        boolean firstParty = given.baseUrl() == null && System.getenv("ANTHROPIC_BASE_URL") == null;
        this.maxTokens = given.maxTokens() != null ? given.maxTokens() : DEFAULT_MAX_TOKENS;
        this.contextWindow = given.contextWindow() != null ? given.contextWindow()
                : adaptive ? ADAPTIVE_CONTEXT_WINDOW : LEGACY_CONTEXT_WINDOW;
        this.thinking = given.thinking() != null ? given.thinking() : adaptive;
        this.thinkingDisplay = given.thinkingDisplay() != null ? given.thinkingDisplay() : "summarized";
        this.effort = given.effort() != null ? (given.effort().isBlank() ? null : given.effort()) : (adaptive ? "high" : null);
        this.fallbacks = given.fallbacks() != null ? given.fallbacks() : firstParty && FALLBACK_MODELS.contains(this.model);
        this.eagerInputStreaming = given.eagerInputStreaming() != null ? given.eagerInputStreaming() : firstParty;
        this.promptCache = given.promptCache() == null || given.promptCache();
        this.extraBody = given.extraBody() == null ? Map.of() : given.extraBody();
        this.client = createClient(given);
    }

    /** 没配的项从环境变量读（ANTHROPIC_API_KEY、ANTHROPIC_BASE_URL 等）。 */
    private static AnthropicClient createClient(Options given) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().fromEnv();
        if (given.apiKey() != null && !given.apiKey().isBlank()) {
            builder.apiKey(given.apiKey());
        }
        if (given.baseUrl() != null) {
            builder.baseUrl(given.baseUrl());
        }
        builder.maxRetries(given.maxRetries() != null ? given.maxRetries() : DEFAULT_SDK_RETRIES);
        builder.timeout(given.timeout() != null ? given.timeout() : DEFAULT_TIMEOUT);
        return builder.build();
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
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens() != null ? request.maxTokens() : maxTokens);
        addSystemPrompt(params, request.system());
        if (promptCache) {
            params.cacheControl(BetaCacheControlEphemeral.builder().build()); // 顶层自动缓存：断点跟着对话尾部走
        }
        addMessages(params, request);
        addTools(params, request);
        if (thinking) {
            params.thinking(BetaThinkingConfigAdaptive.builder().display(BetaThinkingConfigAdaptive.Display.of(thinkingDisplay)).build());
        }
        String requestedEffort = request.options().get("effort") instanceof String value ? value : effort;
        if (requestedEffort != null) {
            params.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(requestedEffort)).build());
        }
        if (fallbacks) {
            params.fallbacksDefault();
            params.addBeta(FALLBACK_BETA);
        }
        extraBody.forEach((key, value) -> params.putAdditionalBodyProperty(key, JsonValue.from(value)));
        return params.build();
    }

    /** 系统提示词单独打一个缓存断点：它在整个会话里不变，命中率最高。 */
    private void addSystemPrompt(MessageCreateParams.Builder params, String systemPrompt) {
        if (systemPrompt.isEmpty()) {
            return;
        }
        BetaTextBlockParam.Builder system = BetaTextBlockParam.builder().text(systemPrompt);
        if (promptCache) {
            system.cacheControl(BetaCacheControlEphemeral.builder().build());
        }
        params.systemOfBetaTextBlockParams(List.of(system.build()));
    }

    /** 领域消息先由 AnthropicWire 转成请求 JSON，再交给 SDK 反序列化成参数对象。 */
    private void addMessages(MessageCreateParams.Builder params, ModelRequest request) {
        for (AnthropicWire.WireMessage message : AnthropicWire.toWire(request.messages())) {
            List<BetaContentBlockParam> blocks = new ArrayList<>();
            for (JsonNode block : message.content()) {
                blocks.add(sdkMapper.convertValue(block, BetaContentBlockParam.class));
            }
            params.addMessage(message.role() == Role.USER ? BetaMessageParam.ofUserOfContentBlockParams(blocks)
                    : BetaMessageParam.ofAssistantOfContentBlockParams(blocks));
        }
    }

    private void addTools(MessageCreateParams.Builder params, ModelRequest request) {
        for (ToolSpec spec : request.tools()) {
            BetaTool.Builder tool = BetaTool.builder().name(spec.name()).description(spec.description())
                    .inputSchema(sdkMapper.convertValue(spec.inputSchema(), BetaTool.InputSchema.class));
            if (eagerInputStreaming) {
                tool.eagerInputStreaming(true);
            }
            params.addTool(tool.build());
        }
    }

    // ------------------------------------------------------------------ 流式调用

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
        MessageCreateParams params = buildParams(request);
        BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
        try (StreamResponse<BetaRawMessageStreamEvent> events = client.beta().messages().createStreaming(params)) {
            Runnable unregister = cancel.onCancel(events::close);
            try {
                for (BetaRawMessageStreamEvent event : events) {
                    accumulator.accumulate(event);
                    forward(event, listener);
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
        return toResponse(accumulator.message());
    }

    /** 增量事件实时回调给引擎：工具调用开始、文字、思考。 */
    private static void forward(BetaRawMessageStreamEvent event, ModelStreamListener listener) {
        event.contentBlockStart().ifPresent(start -> start.contentBlock().toolUse()
                .ifPresent(toolUse -> listener.onToolCallStart(toolUse.id(), toolUse.name())));
        event.contentBlockDelta().ifPresent(delta -> {
            delta.delta().text().ifPresent(text -> listener.onText(text.text()));
            delta.delta().thinking().ifPresent(thinking -> listener.onThinking(thinking.thinking()));
        });
    }

    private ModelResponse toResponse(BetaMessage reply) {
        List<JsonNode> raw = new ArrayList<>();
        for (BetaContentBlock block : reply.content()) {
            raw.add(rawBlock(block));
        }
        raw = AnthropicWire.applyFallbackEchoRule(raw);
        List<ContentBlock> content = AnthropicWire.fromWire(raw);
        TokenUsage usage = new TokenUsage(reply.usage().inputTokens(), reply.usage().outputTokens(),
                reply.usage().cacheReadInputTokens().orElse(0L), reply.usage().cacheCreationInputTokens().orElse(0L), 1);
        String rawStop = reply.stopReason().map(reason -> reason.asString()).orElse(null);
        StopReason stop = mapStop(rawStop);
        if (stop == StopReason.END_TURN && content.stream().anyMatch(ToolCall.class::isInstance)) {
            stop = StopReason.TOOL_USE;
        }
        String details = reply.stopDetails().map(this::toJson).orElse(null);
        String modelName = reply.model().asString();
        Message message = Message.assistant(content, new ProviderPayload(AnthropicWire.PROVIDER, modelName, Json.write(raw)));
        return new ModelResponse(message, stop, usage, modelName, rawStop, details);
    }

    private String toJson(Object value) {
        try {
            return sdkMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 优先用 toParam()（请求格式，可直接回放）；不支持的块类型退回到响应 JSON。 */
    private JsonNode rawBlock(BetaContentBlock block) {
        try {
            return Json.read(sdkMapper.writeValueAsString(block.toParam()));
        } catch (Exception notConvertible) {
            try {
                return Json.read(sdkMapper.writeValueAsString(block));
            } catch (Exception unreadable) {
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

    /** SDK 异常 → 引擎认识的三类：超窗、可重试、不可重试（另有"输出无法解析"）。 */
    private static ModelException translate(RuntimeException e) {
        if (e instanceof ModelException already) {
            return already;
        }
        if (e instanceof AnthropicInvalidDataException) {
            // eager_input_streaming 下无法解析的工具参数：没有完整的 tool_use 可回复，只能重发本轮
            return new MalformedOutputException("unparseable model output: " + e.getMessage(), e);
        }
        if (e instanceof BadRequestException badRequest) {
            String message = String.valueOf(badRequest.getMessage());
            String lower = message.toLowerCase();
            if (lower.contains("prompt is too long") || lower.contains("context window") || lower.contains("too many tokens")) {
                return new ContextOverflowException(message, e);
            }
            return new ModelException(message, false, 400, e);
        }
        if (e instanceof RateLimitException || e instanceof InternalServerException || e instanceof AnthropicIoException
                || e instanceof AnthropicRetryableException) {
            Integer status = e instanceof AnthropicServiceException service ? service.statusCode() : null;
            return new ModelException(e.getMessage(), true, status, e);
        }
        if (e instanceof AnthropicServiceException service) {
            return new ModelException(service.getMessage(), service.statusCode() >= 500, service.statusCode(), e);
        }
        return new ModelException(e.getClass().getSimpleName() + ": " + e.getMessage(), false, null, e);
    }

    @Override
    public void close() {
        client.close();
    }
}
