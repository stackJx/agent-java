package com.agentcore.application.subagent;

import com.agentcore.application.prompt.SystemPromptBuilder;
import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.model.AgentDefinition;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.agent.model.RunResult;
import com.agentcore.domain.agent.service.AgentEngine;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.model.ToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * task 工具：把自包含的子任务交给一个拥有独立上下文的子 Agent。
 *
 * <ul>
 *   <li>大量搜索/阅读的中间结果留在子 Agent 的上下文里，主 Agent 只拿到结论；</li>
 *   <li>模型在一次回复里发起多个 task 时，子 Agent 并行运行；</li>
 *   <li>每个角色可以有自己的提示词、工具白名单和模型。</li>
 * </ul>
 * 权限策略、审批与钩子继承自父引擎，子 Agent 无法绕过。
 */
public final class TaskTool implements Tool {

    private final Map<String, SubAgentProfile> profiles;
    private final Function<String, ModelGateway> modelFactory;
    private volatile AgentEngine parent;

    public TaskTool(Map<String, SubAgentProfile> profiles, Function<String, ModelGateway> modelFactory) {
        this.profiles = new LinkedHashMap<>(profiles == null ? Map.of() : profiles);
        this.profiles.putIfAbsent("general", SubAgentProfile.GENERAL);
        this.modelFactory = modelFactory;
    }

    /** 绑定父引擎（引擎构建完成后调用）。 */
    public TaskTool attach(AgentEngine engine) {
        this.parent = engine;
        return this;
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        String roster = profiles.values().stream().map(p -> "- " + p.name() + ": " + p.description())
                .collect(Collectors.joining("\n"));
        return "Launch a sub-agent with its own fresh context to handle a self-contained task, and get back its final report.\n"
                + "Use it for broad research or searches that would flood your context, or to run independent pieces of work in "
                + "parallel (make several task calls in one response). The sub-agent cannot see this conversation, so the prompt "
                + "must contain everything it needs: the goal, relevant context, constraints, and what to return.\n\n"
                + "Available agent types:\n" + roster;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("description", Map.of("type", "string", "description", "A short (3-6 word) label for the task."));
        props.put("prompt", Map.of("type", "string", "description", "The complete task for the sub-agent."));
        props.put("agent", Map.of("type", "string", "enum", List.copyOf(profiles.keySet()),
                "description", "Which agent type to use (default: general)."));
        return Map.of("type", "object", "properties", props, "required", List.of("description", "prompt"));
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }

    /** 子 Agent 内部的每个工具调用仍受权限策略约束，task 本身无需审批。 */
    @Override
    public PermissionLevel defaultPermission() {
        return PermissionLevel.ALLOW;
    }

    @Override
    public Duration timeout() {
        return null;
    }

    @Override
    public int maxOutputChars() {
        return 50_000;
    }

    @Override
    public String permissionSubject(Map<String, Object> arguments) {
        return String.valueOf(arguments.getOrDefault("description", ""));
    }

    @Override
    public ToolOutput execute(Map<String, Object> args, ToolContext ctx) {
        AgentEngine root = parent;
        if (root == null) {
            throw new ToolException("task tool is not attached to an agent engine");
        }
        String agentName = String.valueOf(args.getOrDefault("agent", "general"));
        SubAgentProfile profile = profiles.get(agentName);
        if (profile == null) {
            throw new ToolException("unknown agent type '" + agentName + "'");
        }
        EngineSettings parentSettings = root.definition().settings();
        int depth = ctx.depth() + 1;
        if (depth > parentSettings.maxDepth()) {
            throw new ToolException("maximum sub-agent depth reached; do the work directly instead");
        }
        ModelGateway model = profile.model() != null && modelFactory != null ? modelFactory.apply(profile.model()) : root.model();

        List<String> exclude = new ArrayList<>(List.of("ask_user"));
        if (depth >= parentSettings.maxDepth()) {
            exclude.add("task");
        }
        ToolRegistry childTools = root.tools().filtered(profile.tools(), exclude);
        String prompt = new SystemPromptBuilder()
                .name(root.definition().name() + "/" + profile.name())
                .basePrompt(profile.systemPrompt())
                .cwd(parentSettings.cwd())
                .instructions(profile.instructions())
                .projectInstructions(false)
                .section(SystemPromptBuilder.SUBAGENT_NOTE)
                .build();
        AgentEngine child = root.toBuilder()
                .definition(new AgentDefinition(root.definition().name() + "/" + profile.name(), prompt,
                        parentSettings.withMaxTurns(profile.maxTurns() > 0 ? profile.maxTurns() : 50)))
                .model(model)
                .tools(childTools)
                .repository(null)
                .depth(depth)
                .build();

        String label = String.valueOf(args.getOrDefault("description", profile.name()));
        RunControl control = new RunControl();
        Runnable unregister = ctx.cancel().onCancel(control::cancel);
        RunResult result;
        try {
            result = child.run(Conversation.start(), List.of(new TextBlock(String.valueOf(args.get("prompt")))), e -> {
                if (e instanceof AgentEvent.ToolStarted ts) {
                    ctx.report("[" + label + "] " + ts.summary());
                }
            }, control);
        } finally {
            unregister.run();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("usage", result.usage());
        meta.put("turns", result.turns());
        meta.put("agent", profile.name());
        meta.put("stop_reason", result.stopReason().wire());
        if (result.error() != null) {
            return new ToolOutput(List.of(new TextBlock("Sub-agent failed: " + result.error() + "\nPartial output:\n" + result.text())),
                    true, meta);
        }
        String text = result.text().isBlank() ? "(the sub-agent finished without a final report)" : result.text().strip();
        if (result.stopReason() != StopReason.END_TURN) {
            text += "\n\n[sub-agent stopped: " + result.stopReason().wire() + "]";
        }
        return ToolOutput.text(text, meta);
    }
}
