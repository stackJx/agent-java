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

    /** Responses 默认的输出上限：推理模型的思考也算输出，留足空间。 */
    private static final int RESPONSES_MAX_TOKENS = 32_000;
    private static final int CHAT_MAX_TOKENS = 16_000;
    /** 没有预设、也没配置时假定的上下文窗口。 */
    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;

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
            return new ScriptedModelGateway().otherwise(request -> ScriptedModelGateway.reply("(mock) 你好，我是一个离线假模型。"));
        }
        ModelSpec parsed = ModelSpec.parse(spec);
        Config config = configFor(parsed.provider());
        String type = config.string("type", switch (parsed.provider()) {
            case "anthropic" -> "anthropic";
            case "openai" -> "openai-responses"; // OpenAI 新模型的函数调用只支持 Responses
            default -> "openai";
        });
        return switch (type) {
            case "anthropic" -> anthropic(parsed.model(), config);
            case "openai-responses", "responses" -> openAiResponses(parsed, config);
            case "openai", "openai-chat" -> openAiChat(parsed, config);
            default -> throw new IllegalArgumentException("未知的 provider type: " + type + "（可选 anthropic / openai / openai-responses）");
        };
    }

    /** "provider:model"；省略 provider 时只认 claude 开头的模型名。 */
    private record ModelSpec(String provider, String model) {
        static ModelSpec parse(String spec) {
            int colon = spec.indexOf(':');
            if (colon > 0) {
                return new ModelSpec(spec.substring(0, colon), spec.substring(colon + 1));
            }
            if (spec.startsWith("claude")) {
                return new ModelSpec("anthropic", spec);
            }
            throw new IllegalArgumentException("模型需写成 '<provider>:<model>'，例如 deepseek:deepseek-v4-pro，收到: " + spec);
        }
    }

    /** 配置文件 providers.&lt;name&gt; 段；其中 options 子段的键会摊平到同一层。 */
    private Config configFor(String provider) {
        Map<String, Object> values = new LinkedHashMap<>(providers.getOrDefault(provider, Map.of()));
        if (values.get("options") instanceof Map<?, ?> nested) {
            nested.forEach((key, value) -> values.put(String.valueOf(key), value));
        }
        return new Config(values);
    }

    private ModelGateway anthropic(String model, Config config) {
        String apiKey = config.string("api_key", null);
        String keyEnv = config.string("api_key_env", null);
        if (apiKey == null && keyEnv != null) {
            apiKey = env.apply(keyEnv); // 都没配时 SDK 自己读 ANTHROPIC_API_KEY
        }
        return new AnthropicModelGateway(model, new AnthropicModelGateway.Options(
                apiKey,
                config.string("base_url", null),
                config.integer("max_tokens"),
                config.integer("context_window"),
                config.bool("thinking"),
                config.string("thinking_display", null),
                config.string("effort", null),
                config.bool("fallbacks"),
                config.bool("eager_input_streaming"),
                config.bool("prompt_cache"),
                config.integer("max_retries"),
                config.seconds("timeout_seconds"),
                config.map("extra_body")), config.flag("inherit_environment", true));
    }

    private ModelGateway openAiResponses(ModelSpec spec, Config config) {
        ProviderPresets.Preset preset = ProviderPresets.PRESETS.get(spec.provider());
        return new OpenAiResponsesModelGateway(spec.model(), new OpenAiResponsesModelGateway.Options(
                spec.provider(),
                config.string("base_url", preset != null ? preset.baseUrl() : null),
                openAiKey(config, preset),
                config.integer("max_tokens", RESPONSES_MAX_TOKENS),
                config.integer("context_window", preset != null ? preset.contextWindow() : DEFAULT_CONTEXT_WINDOW),
                config.string("reasoning_effort", config.string("effort", null)),
                config.string("reasoning_summary", null),
                config.has("store") && config.flag("store", false),
                config.seconds("timeout_seconds"),
                config.headers(),
                config.map("extra_body")));
    }

    private ModelGateway openAiChat(ModelSpec spec, Config config) {
        ProviderPresets.Preset preset = ProviderPresets.PRESETS.get(spec.provider());
        return new OpenAiCompatibleModelGateway(spec.model(), new OpenAiCompatibleModelGateway.Options(
                spec.provider(),
                config.string("base_url", preset != null ? preset.baseUrl() : null),
                openAiKey(config, preset),
                config.integer("max_tokens", CHAT_MAX_TOKENS),
                config.string("max_tokens_param", preset != null ? preset.maxTokensParam() : "max_tokens"),
                config.integer("context_window", preset != null ? preset.contextWindow() : DEFAULT_CONTEXT_WINDOW),
                config.string("reasoning_effort", null),
                config.flag("echo_reasoning", preset == null || preset.echoReasoning()),
                config.flag("stream_usage", true),
                config.seconds("timeout_seconds"),
                config.headers(),
                config.map("extra_body")));
    }

    /** API Key 的查找顺序：api_key → api_key_env（或预设的环境变量名）指向的环境变量 → OPENAI_API_KEY。 */
    private String openAiKey(Config config, ProviderPresets.Preset preset) {
        String apiKey = config.string("api_key", preset != null ? preset.apiKey() : null);
        String keyEnv = config.string("api_key_env", preset != null ? preset.apiKeyEnv() : null);
        if (apiKey == null && keyEnv != null) {
            apiKey = env.apply(keyEnv);
        }
        return apiKey != null ? apiKey : env.apply("OPENAI_API_KEY");
    }

    /** 一个 provider 的配置项（来自 YAML，值可能是字符串也可能是数字/布尔），按需要的类型取值。 */
    private record Config(Map<String, Object> values) {

        boolean has(String key) {
            return values.get(key) != null;
        }

        /** 空白字符串也当作没配。 */
        String string(String key, String fallback) {
            Object value = values.get(key);
            return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
        }

        Integer integer(String key) {
            Object value = values.get(key);
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        }

        int integer(String key, int fallback) {
            Object value = values.get(key);
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        }

        Boolean bool(String key) {
            Object value = values.get(key);
            return value == null ? null : Boolean.valueOf(String.valueOf(value));
        }

        /** 键存在就按它的值，否则用 fallback（注意：键存在但值为 null 时按 false 处理）。 */
        boolean flag(String key, boolean fallback) {
            return values.containsKey(key) ? Boolean.parseBoolean(String.valueOf(values.get(key))) : fallback;
        }

        Duration seconds(String key) {
            Object value = values.get(key);
            return value == null ? null : Duration.ofSeconds(Long.parseLong(String.valueOf(value)));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map(String key) {
            return values.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        @SuppressWarnings("unchecked")
        Map<String, String> headers() {
            return values.get("headers") instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
        }
    }
}
