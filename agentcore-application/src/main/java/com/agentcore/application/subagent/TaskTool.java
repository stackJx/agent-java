package com.agentcore.application.subagent;

import com.agentcore.application.prompt.SystemPromptBuilder;
import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.agent.model.AgentDefinition;
import com.agentcore.domain.agent.model.EngineSettings;
import com.agentcore.domain.agent.model.RunControl;
import com.agentcore.domain.agent.model.RunModel;
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

    /** 档案没指定 maxTurns 时，子 Agent 最多跑几轮。 */
    private static final int DEFAULT_MAX_TURNS = 50;

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
        String roster = profiles.values().stream().map(profile -> "- " + profile.name() + ": " + profile.description())
                .collect(Collectors.joining("\n"));
        return "Launch a sub-agent with its own fresh context to handle a self-contained task, and get back its final report.\n"
                + "Use it for broad research or searches that would flood your context, or to run independent pieces of work in "
                + "parallel (make several task calls in one response). The sub-agent cannot see this conversation, so the prompt "
                + "must contain everything it needs: the goal, relevant context, constraints, and what to return.\n\n"
                + "Available agent types:\n" + roster;
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", Map.of("type", "string", "description", "A short (3-6 word) label for the task."));
        properties.put("prompt", Map.of("type", "string", "description", "The complete task for the sub-agent."));
        properties.put("agent", Map.of("type", "string", "enum", List.copyOf(profiles.keySet()),
                "description", "Which agent type to use (default: general)."));
        return Map.of("type", "object", "properties", properties, "required", List.of("description", "prompt"));
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
        SubAgentProfile profile = profileFor(String.valueOf(args.getOrDefault("agent", "general")));
        int depth = ctx.depth() + 1;
        if (depth > root.definition().settings().maxDepth()) {
            throw new ToolException("maximum sub-agent depth reached; do the work directly instead");
        }
        RunModel runModel = ctx.service(RunModel.class);
        ModelGateway inheritedModel = runModel == null ? root.model() : runModel.gateway();
        AgentEngine child = createChild(root, profile, depth, inheritedModel);
        String label = String.valueOf(args.getOrDefault("description", profile.name()));
        RunResult result = runChild(child, String.valueOf(args.get("prompt")), label, ctx);
        return toOutput(result, profile);
    }

    private SubAgentProfile profileFor(String agentName) {
        SubAgentProfile profile = profiles.get(agentName);
        if (profile == null) {
            throw new ToolException("unknown agent type '" + agentName + "'");
        }
        return profile;
    }

    /**
     * 从父引擎派生子引擎：自己的系统提示词、模型（档案指定了就换）、工具子集；不落盘。
     * 子 Agent 不能向用户提问；到了最大深度时连 task 也拿掉，防止无限嵌套。
     */
    private AgentEngine createChild(AgentEngine root, SubAgentProfile profile, int depth, ModelGateway inheritedModel) {
        EngineSettings parentSettings = root.definition().settings();
        String name = root.definition().name() + "/" + profile.name();
        // 角色未指定模型时继承父运行的快照，而不是启动时的默认模型。
        ModelGateway model = profile.model() != null && modelFactory != null ? modelFactory.apply(profile.model()) : inheritedModel;

        List<String> excluded = new ArrayList<>(List.of("ask_user"));
        if (depth >= parentSettings.maxDepth()) {
            excluded.add("task");
        }
        String systemPrompt = new SystemPromptBuilder()
                .name(name)
                .basePrompt(profile.systemPrompt())
                .cwd(parentSettings.cwd())
                .instructions(profile.instructions())
                .projectInstructions(false)
                .section(SystemPromptBuilder.SUBAGENT_NOTE)
                .build();
        int maxTurns = profile.maxTurns() > 0 ? profile.maxTurns() : DEFAULT_MAX_TURNS;
        return root.toBuilder()
                .definition(new AgentDefinition(name, systemPrompt, parentSettings.withMaxTurns(maxTurns)))
                .model(model)
                .tools(root.tools().filtered(profile.tools(), excluded))
                .repository(null)
                .depth(depth)
                .build();
    }

    /** 在全新的会话里跑子 Agent。父运行取消时一起取消；子 Agent 每调用一个工具就向父运行报一次进度。 */
    private static RunResult runChild(AgentEngine child, String prompt, String label, ToolContext ctx) {
        RunControl control = new RunControl();
        Runnable unregister = ctx.cancel().onCancel(control::cancel);
        try {
            return child.run(Conversation.start(), List.of(new TextBlock(prompt)), event -> {
                if (event instanceof AgentEvent.ToolStarted started) {
                    ctx.report("[" + label + "] " + started.summary());
                }
            }, control);
        } finally {
            unregister.run();
        }
    }

    /** 子 Agent 的最终答复就是工具结果；用量放在 metadata.usage 里，引擎会把它计入父运行。 */
    private static ToolOutput toOutput(RunResult result, SubAgentProfile profile) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("usage", result.usage());
        metadata.put("turns", result.turns());
        metadata.put("agent", profile.name());
        metadata.put("stop_reason", result.stopReason().wire());
        if (result.error() != null) {
            String text = "Sub-agent failed: " + result.error() + "\nPartial output:\n" + result.text();
            return new ToolOutput(List.of(new TextBlock(text)), true, metadata);
        }
        String text = result.text().isBlank() ? "(the sub-agent finished without a final report)" : result.text().strip();
        if (result.stopReason() != StopReason.END_TURN) {
            text += "\n\n[sub-agent stopped: " + result.stopReason().wire() + "]";
        }
        return ToolOutput.text(text, metadata);
    }
}
