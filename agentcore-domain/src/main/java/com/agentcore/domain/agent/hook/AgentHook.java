package com.agentcore.domain.agent.hook;

import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import java.util.Map;

/**
 * 钩子：在不改动引擎的前提下插入自定义逻辑（审计、脱敏、风控、注入上下文……）。
 * 所有方法都有默认实现，按需覆盖。
 */
public interface AgentHook {

    /** 可返回修改后的请求。 */
    default ModelRequest beforeModel(ModelRequest request) {
        return request;
    }

    default void afterModel(ModelResponse response) {
    }

    /** 可拦截或改写参数。arguments 已通过 schema 校验。 */
    default HookDecision beforeTool(ToolCall call, Tool tool, Map<String, Object> arguments, ToolContext context) {
        return HookDecision.proceed();
    }

    /** 可返回修改后的输出。 */
    default ToolOutput afterTool(ToolCall call, ToolOutput output, ToolContext context) {
        return output;
    }
}
