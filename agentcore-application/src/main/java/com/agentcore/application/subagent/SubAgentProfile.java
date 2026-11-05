package com.agentcore.application.subagent;

import java.util.List;

/**
 * 子 Agent 角色定义。
 *
 * @param tools 工具白名单（支持通配符）；null 表示继承父 Agent 的全部工具
 * @param model 模型规格，例如 "deepseek:deepseek-flash"；null 表示与父 Agent 相同
 */
public record SubAgentProfile(String name, String description, String systemPrompt, String instructions,
                              List<String> tools, String model, int maxTurns) {

    public static final SubAgentProfile GENERAL = new SubAgentProfile("general",
            "General-purpose agent for researching questions, searching code or documents, and executing multi-step tasks. Returns a final report.",
            null, "", null, null, 50);
}
