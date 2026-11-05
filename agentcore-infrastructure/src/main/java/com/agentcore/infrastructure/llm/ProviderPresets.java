package com.agentcore.infrastructure.llm;

import java.util.Map;

/** 常用 OpenAI 兼容服务的预设。model 写成 "deepseek:deepseek-v4-pro" 即可直接使用。 */
public final class ProviderPresets {

    public record Preset(String baseUrl, String apiKeyEnv, String apiKey, String maxTokensParam, int contextWindow,
                         boolean echoReasoning) {
    }

    private static final Preset DEEPSEEK = new Preset("https://api.deepseek.com", "DEEPSEEK_API_KEY", null, "max_tokens", 128_000, true);
    private static final Preset QWEN = new Preset("https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", null, "max_tokens", 128_000, true);
    private static final Preset MOONSHOT = new Preset("https://api.moonshot.cn/v1", "MOONSHOT_API_KEY", null, "max_tokens", 128_000, true);
    private static final Preset ZHIPU = new Preset("https://open.bigmodel.cn/api/paas/v4", "ZHIPUAI_API_KEY", null, "max_tokens", 128_000, true);

    public static final Map<String, Preset> PRESETS = Map.ofEntries(
            Map.entry("openai", new Preset("https://api.openai.com/v1", "OPENAI_API_KEY", null, "max_completion_tokens", 128_000, false)),
            Map.entry("deepseek", DEEPSEEK),
            Map.entry("qwen", QWEN), Map.entry("dashscope", QWEN),
            Map.entry("moonshot", MOONSHOT), Map.entry("kimi", MOONSHOT),
            Map.entry("zhipu", ZHIPU), Map.entry("glm", ZHIPU),
            Map.entry("openrouter", new Preset("https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", null, "max_tokens", 128_000, true)),
            Map.entry("ollama", new Preset("http://localhost:11434/v1", null, "ollama", "max_tokens", 32_000, true)),
            Map.entry("vllm", new Preset("http://localhost:8000/v1", null, "EMPTY", "max_tokens", 32_000, true)));

    private ProviderPresets() {
    }
}
