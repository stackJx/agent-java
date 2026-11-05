package com.example.agent;

import com.agentcore.domain.agent.hook.AgentHook;
import com.agentcore.domain.agent.hook.HookDecision;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 业务规则写成钩子：单笔退款超过 100 元转人工，模型会收到拦截原因并据此回复用户。 */
@Component
public class RefundLimitHook implements AgentHook {

    @Override
    public HookDecision beforeTool(ToolCall call, Tool tool, Map<String, Object> args, ToolContext ctx) {
        if (call.name().equals("refund") && args.get("amount") instanceof Number n && n.doubleValue() > 100) {
            return HookDecision.block("单笔退款超过 100 元需要人工客服处理，请告知用户已转人工，24 小时内会有专人联系");
        }
        return HookDecision.proceed();
    }
}
