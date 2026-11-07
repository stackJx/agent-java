package com.agentcore.spring.web;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 页面上配置的一个模型（不可变）。
 *
 * @param id            配置 ID（小写字母开头，只含小写字母、数字、- 和 _）
 * @param label         显示名称
 * @param type          协议：anthropic / openai / openai-responses / mock
 * @param model         厂商那边的模型名称
 * @param baseUrl       接口地址；mock 为空
 * @param apiKey        API key，没有时为空字符串。只在服务端使用，不会通过任何接口返回
 * @param maxTokens     单次回复的输出上限
 * @param contextWindow 上下文窗口大小
 */
record ModelProfile(String id, String label, String type, String model, String baseUrl,
                    String apiKey, int maxTokens, int contextWindow) {

    boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    /** 给页面看的样子：不含 API key，只说有没有。 */
    ObjectNode publicView() {
        return Json.object()
                .put("id", id)
                .put("label", label)
                .put("type", type)
                .put("model", model)
                .put("base_url", baseUrl)
                .put("has_api_key", hasApiKey())
                .put("max_tokens", maxTokens)
                .put("context_window", contextWindow);
    }

    /** 写进服务端配置文件的样子：页面看到的字段，加上 API key。 */
    ObjectNode storedView() {
        return publicView().put("api_key", apiKey);
    }

    /** 日志、异常里打印配置时不带出 API key。 */
    @Override
    public String toString() {
        return "ModelProfile[" + id + ", redacted]";
    }
}
