package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.gateway.UserPrompter;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务规划与人机交互：todo_write / ask_user。 */
public final class InteractionTools {

    private InteractionTools() {
    }

    public static Tool todoWrite() {
        Map<String, Object> item = Schemas.object()
                .string("content", "What the step is.", true)
                .enumOf("status", List.of("pending", "in_progress", "completed"), "Step status.", true)
                .build();
        return FunctionTool.builder("todo_write")
                .description("Create or update the task list for the current work. Send the COMPLETE list every time. "
                        + "Use it for multi-step tasks: one item per concrete step, keep exactly one item in_progress, "
                        + "mark items completed as soon as they are done.")
                .schema(Schemas.object().array("todos", item, "The full, updated todo list.", true).build())
                .permission(PermissionLevel.ALLOW)
                .handler((args, ctx) -> {
                    List<Map<String, Object>> todos = new ArrayList<>();
                    for (Object todo : (List<?>) args.get("todos")) {
                        todos.add(new LinkedHashMap<>((Map<String, Object>) todo));
                    }
                    ctx.state().put("todos", todos);
                    long done = todos.stream().filter(todo -> "completed".equals(todo.get("status"))).count();
                    StringBuilder summary = new StringBuilder("Todo list updated (" + done + "/" + todos.size() + " done):");
                    for (Map<String, Object> todo : todos) {
                        String mark = switch (String.valueOf(todo.get("status"))) {
                            case "completed" -> "[x]";
                            case "in_progress" -> "[~]";
                            default -> "[ ]";
                        };
                        summary.append('\n').append(mark).append(' ').append(todo.get("content"));
                    }
                    return ToolOutput.text(summary.toString(), Map.of("todos", todos));
                }).build();
    }

    public static Tool askUser() {
        return FunctionTool.builder("ask_user")
                .description("Ask the user a clarifying question and wait for the answer. Only use this when you are truly blocked on a decision that only the user can make.")
                .schema(Schemas.object()
                        .string("question", "The question to ask.", true)
                        .array("options", Schemas.string(null), "Optional list of suggested answers.", false)
                        .build())
                .permission(PermissionLevel.ALLOW)
                .timeout(null)
                .handler((args, ctx) -> {
                    UserPrompter prompter = ctx.service(UserPrompter.class);
                    if (prompter == null) {
                        throw new ToolException("No interactive user is available. Make a reasonable assumption, state it, and continue.");
                    }
                    List<String> options = args.get("options") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
                    return ToolOutput.text("User answered: " + prompter.ask((String) args.get("question"), options));
                }).build();
    }
}
