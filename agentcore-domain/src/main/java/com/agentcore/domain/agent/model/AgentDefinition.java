package com.agentcore.domain.agent.model;

import java.util.Objects;

/**
 * Agent 定义（值对象）：名称 + 最终系统提示词 + 运行参数。
 * 系统提示词在创建时就固定下来（prompt cache 友好），由应用层的 SystemPromptBuilder 组装。
 */
public record AgentDefinition(String name, String systemPrompt, EngineSettings settings) {
    public AgentDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(settings, "settings");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }
}
