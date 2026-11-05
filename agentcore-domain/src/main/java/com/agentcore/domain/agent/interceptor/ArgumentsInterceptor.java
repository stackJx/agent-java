package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.SchemaValidator;
import java.util.List;
import java.util.Map;

/**
 * 关卡 2、3：参数是合法 JSON（适配器解析时已经记下 parseError），并且符合工具的 JSON Schema。
 * 校验时顺带做温和的类型转换（例如 "3" → 3），转换后的值才往下传。
 */
public final class ArgumentsInterceptor implements ToolInterceptor {

    /** 校验错误最多告诉模型几条，太多了反而干扰它修正。 */
    private static final int MAX_REPORTED_ERRORS = 10;

    @Override
    @SuppressWarnings("unchecked")
    public ToolOutput intercept(ToolInvocation invocation, Chain chain) {
        // 2. 参数是合法 JSON
        String parseError = invocation.call().parseError();
        if (parseError != null) {
            return ToolOutput.error("Error: invalid tool arguments: " + parseError + ". Send a valid JSON object matching the schema.");
        }
        // 3. 参数符合 Schema
        SchemaValidator.Result result = SchemaValidator.validate(invocation.arguments(), invocation.tool().inputSchema());
        if (!result.valid()) {
            List<String> errors = result.errors().subList(0, Math.min(MAX_REPORTED_ERRORS, result.errors().size()));
            return ToolOutput.error("Error: invalid arguments for " + invocation.tool().name() + ":\n- " + String.join("\n- ", errors));
        }
        invocation.setArguments(result.value() instanceof Map<?, ?> coerced ? (Map<String, Object>) coerced : Map.of());
        return chain.proceed(invocation);
    }
}
