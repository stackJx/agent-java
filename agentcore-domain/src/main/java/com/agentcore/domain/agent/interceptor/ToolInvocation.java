package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一次工具调用在拦截器链里传递的上下文：模型发起的调用、找到的工具、当前参数、执行上下文。
 * 参数会被前面的拦截器修改（Schema 校验后的类型转换、钩子改写），后面的拦截器和工具看到的是修改后的值。
 */
public final class ToolInvocation {

    private final ToolCall call;
    private final Tool tool;
    private final ToolContext context;
    private final Consumer<AgentEvent> events;
    private volatile Map<String, Object> arguments;

    public ToolInvocation(ToolCall call, Tool tool, ToolContext context, Consumer<AgentEvent> events) {
        this.call = call;
        this.tool = tool;
        this.context = context;
        this.events = events;
        this.arguments = call.arguments();
    }

    /** 模型发起的原始调用（id、工具名、原始参数）。 */
    public ToolCall call() {
        return call;
    }

    public Tool tool() {
        return tool;
    }

    /** 工作目录、会话 ID、会话级状态、取消信号、宿主注入的服务…… */
    public ToolContext context() {
        return context;
    }

    /** 当前参数（可能已被前面的拦截器转换或改写）。 */
    public Map<String, Object> arguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments == null ? Map.of() : arguments;
    }

    /** 发出一个引擎事件（例如审批结论、工具开始执行）。 */
    public void emit(AgentEvent event) {
        events.accept(event);
    }
}
