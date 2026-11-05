package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.SchemaValidator;
import java.util.Map;

/**
 * 关卡 2、3：参数是合法 JSON（适配器解析时已经记下 parseError），并且符合工具的 JSON Schema。
 * 校验时顺带做温和的类型转换（例如 "3" → 3），转换后的值才往下传。
 */
public final class ArgumentsInterceptor implements ToolInterceptor {

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutput intercept(ToolInvocation inv, Chain chain) {
        if (inv.call().parseError() != null) {
            return ToolOutput.error("Error: invalid tool arguments: " + inv.call().parseError()
                    + ". Send a valid JSON object matching the schema.");
        }
        SchemaValidator.Result vr = SchemaValidator.validate(inv.arguments(), inv.tool().inputSchema());
        if (!vr.valid()) {
            return ToolOutput.error("Error: invalid arguments for " + inv.tool().name() + ":\n- "
                    + String.join("\n- ", vr.errors().subList(0, Math.min(10, vr.errors().size()))));
        }
        inv.setArguments(vr.value() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
        return chain.proceed(inv);
    }
}
