package com.agentcore.infrastructure.llm;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.infrastructure.llm.anthropic.AnthropicModelGateway;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.infrastructure.llm.openai.OpenAiCompatibleModelGateway;
import com.agentcore.infrastructure.llm.openai.OpenAiResponsesModelGateway;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 根据 {@code "<provider>:<model>"} 创建网关：
 * <ul>
 *   <li>{@code anthropic:claude-opus-5-5}（或直接写 {@code claude-xxx}）→ 官方 Anthropic SDK</li>
 *   <li>{@code openai:gpt-6-astra} → OpenAI Responses API（{@code /responses}）</li>
 *   <li>{@code deepseek:deepseek-v4-pro} / {@code qwen:qwen-max} → OpenAI Chat Completions 兼容预设</li>
 *   <li>{@code myvllm:Qwen3-32B} → 配置文件 providers 段自定义（type / base_url / api_key ...）；
 *       type 取值：{@code anthropic}、{@code openai}（Chat Completions）、{@code openai-responses}（Responses）</li>
 *   <li>{@code mock} → 离线假模型</li>
 * </ul>
 */
public final class ModelGatewayFactory implements Function<String, ModelGateway> {

    private final Map<String, Map<String, Object>> providers;
    private final Function<String, String> env;

    public ModelGatewayFactory(Map<String, Map<String, Object>> providers) {
        this(providers, System::getenv);
    }

    public ModelGatewayFactory(Map<String, Map<String, Object>> providers, Function<String, String> env) {
        this.providers = providers == null ? Map.of() : providers;
        this.env = env;
    }

    /** 没配置模型时，按已有的 API Key 环境变量推断一个默认模型；都没有时返回 null。 */
    public static String defaultModel(Function<String, String> env) {
        if (env.apply("AGENTCORE_MODEL") != null) {
            return env.apply("AGENTCORE_MODEL");
        }
        if (env.apply("ANTHROPIC_API_KEY") != null || env.apply("ANTHROPIC_AUTH_TOKEN") != null) {
            return "anthropic:claude-opus-5-5";
        }
        if (env.apply("DEEPSEEK_API_KEY") != null) {
            return "deepseek:deepseek-v4-pro";
        }
        if (env.apply("OPENAI_API_KEY") != null) {
            return "openai:gpt-5";
        }
        return null;
    }

    @Override
    public ModelGateway apply(String spec) {
        return create(spec);
    }

    public ModelGateway create(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("model spec is empty");
        }
        if (spec.equals("mock")) {
            return new ScriptedModelGateway().otherwise(r -> ScriptedModelGateway.reply("(mock) 你好，我是一个离线假模型。"));
        }
        String provider;
        String model;
        int idx = spec.indexOf(':');
        if (idx > 0) {
            provider = spec.substring(0, idx);
            model = spec.substring(idx + 1);
        } else if (spec.startsWith("claude")) {
            provider = "anthropic";
            model = spec;
        } else {
            throw new IllegalArgumentException("模型需写成 '<provider>:<model>'，例如 deepseek:deepseek-v4-pro，收到: " + spec);
        }
        Map<String, Object> cfg = new LinkedHashMap<>(providers.getOrDefault(provider, Map.of()));
        if (cfg.get("options") instanceof Map<?, ?> opts) {
            opts.forEach((k, v) -> cfg.put(String.valueOf(k), v));
        }
        String type = str(cfg.get("type"), switch (provider) {
            case "anthropic" -> "anthropic";
            case "openai" -> "openai-responses"; // OpenAI 新模型的函数调用只支持 Responses
            default -> "openai";
        });
        if (type.equals("anthropic")) {
            String key = str(cfg.get("api_key"), null);
            if (key == null && cfg.get("api_key_env") != null) {
                key = env.apply(String.valueOf(cfg.get("api_key_env")));
            }
            return new AnthropicModelGateway(model, new AnthropicModelGateway.Options(
                    key, str(cfg.get("base_url"), null), intOrNull(cfg.get("max_tokens")), intOrNull(cfg.get("context_window")),
                    boolOrNull(cfg.get("thinking")), str(cfg.get("thinking_display"), null), str(cfg.get("effort"), null),
                    boolOrNull(cfg.get("fallbacks")), boolOrNull(cfg.get("eager_input_streaming")), boolOrNull(cfg.get("prompt_cache")),
                    intOrNull(cfg.get("max_retries")), durationOrNull(cfg.get("timeout_seconds")), mapOf(cfg.get("extra_body"))));
        }
        ProviderPresets.Preset p = ProviderPresets.PRESETS.get(provider);
        String keyEnv = str(cfg.get("api_key_env"), p != null ? p.apiKeyEnv() : null);
        String key = str(cfg.get("api_key"), p != null ? p.apiKey() : null);
        if (key == null && keyEnv != null) {
            key = env.apply(keyEnv);
        }
        if (key == null) {
            key = env.apply("OPENAI_API_KEY");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> headers = cfg.get("headers") instanceof Map<?, ?> h ? (Map<String, String>) h : Map.of();
        if (type.equals("openai-responses") || type.equals("responses")) {
            return new OpenAiResponsesModelGateway(model, new OpenAiResponsesModelGateway.Options(
                    provider,
                    str(cfg.get("base_url"), p != null ? p.baseUrl() : null),
                    key,
                    intOr(cfg.get("max_tokens"), 32_000), // 推理模型的思考也算输出，留足空间
                    intOr(cfg.get("context_window"), p != null ? p.contextWindow() : 128_000),
                    str(cfg.get("reasoning_effort"), str(cfg.get("effort"), null)),
                    str(cfg.get("reasoning_summary"), null),
                    cfg.get("store") != null && Boolean.parseBoolean(String.valueOf(cfg.get("store"))),
                    durationOrNull(cfg.get("timeout_seconds")),
                    headers,
                    mapOf(cfg.get("extra_body"))));
        }
        if (!type.equals("openai") && !type.equals("openai-chat")) {
            throw new IllegalArgumentException("未知的 provider type: " + type + "（可选 anthropic / openai / openai-responses）");
        }
        return new OpenAiCompatibleModelGateway(model, new OpenAiCompatibleModelGateway.Options(
                provider,
                str(cfg.get("base_url"), p != null ? p.baseUrl() : null),
                key,
                intOr(cfg.get("max_tokens"), 16_000),
                str(cfg.get("max_tokens_param"), p != null ? p.maxTokensParam() : "max_tokens"),
                intOr(cfg.get("context_window"), p != null ? p.contextWindow() : 128_000),
                str(cfg.get("reasoning_effort"), null),
                cfg.containsKey("echo_reasoning") ? Boolean.parseBoolean(String.valueOf(cfg.get("echo_reasoning")))
                        : p == null || p.echoReasoning(),
                !cfg.containsKey("stream_usage") || Boolean.parseBoolean(String.valueOf(cfg.get("stream_usage"))),
                durationOrNull(cfg.get("timeout_seconds")),
                headers,
                mapOf(cfg.get("extra_body"))));
    }

    private static String str(Object v, String def) {
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    private static Integer intOrNull(Object v) {
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }

    private static int intOr(Object v, int def) {
        return v == null ? def : Integer.parseInt(String.valueOf(v));
    }

    private static Boolean boolOrNull(Object v) {
        return v == null ? null : Boolean.valueOf(String.valueOf(v));
    }

    private static Duration durationOrNull(Object v) {
        return v == null ? null : Duration.ofSeconds(Long.parseLong(String.valueOf(v)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
