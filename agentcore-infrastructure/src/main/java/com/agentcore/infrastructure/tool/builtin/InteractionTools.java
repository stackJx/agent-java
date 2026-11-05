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
                .handler((a, ctx) -> {
                    List<Map<String, Object>> todos = new ArrayList<>();
                    for (Object o : (List<?>) a.get("todos")) {
                        todos.add(new LinkedHashMap<>((Map<String, Object>) o));
                    }
                    ctx.state().put("todos", todos);
                    long done = todos.stream().filter(t -> "completed".equals(t.get("status"))).count();
                    StringBuilder sb = new StringBuilder("Todo list updated (" + done + "/" + todos.size() + " done):");
                    for (Map<String, Object> t : todos) {
                        String mark = switch (String.valueOf(t.get("status"))) {
                            case "completed" -> "[x]";
                            case "in_progress" -> "[~]";
                            default -> "[ ]";
                        };
                        sb.append('\n').append(mark).append(' ').append(t.get("content"));
                    }
                    return ToolOutput.text(sb.toString(), Map.of("todos", todos));
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
                .handler((a, ctx) -> {
                    UserPrompter prompter = ctx.service(UserPrompter.class);
                    if (prompter == null) {
                        throw new ToolException("No interactive user is available. Make a reasonable assumption, state it, and continue.");
                    }
                    List<String> options = a.get("options") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
                    return ToolOutput.text("User answered: " + prompter.ask((String) a.get("question"), options));
                }).build();
    }
}
