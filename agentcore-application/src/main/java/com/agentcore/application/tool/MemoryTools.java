package com.agentcore.application.tool;

import com.agentcore.domain.memory.model.MemoryEntry;
import com.agentcore.domain.memory.model.MemoryType;
import com.agentcore.domain.memory.repository.MemoryRepository;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 长期记忆工具：memory_save / memory_read / memory_search / memory_delete。 */
public final class MemoryTools {

    private MemoryTools() {
    }

    public static List<Tool> create(MemoryRepository repo) {
        List<String> types = Arrays.stream(MemoryType.values()).map(MemoryType::wire).toList();
        Tool save = FunctionTool.builder("memory_save")
                .description("Save (create or overwrite) a long-term memory that persists across sessions.")
                .schema(Schemas.object()
                        .string("name", "Short kebab-case identifier, e.g. \"user-prefers-concise-answers\".", true)
                        .string("description", "One-line summary used to decide relevance later.", true)
                        .string("content", "The memory itself. For feedback/project memories, include why it matters and how to apply it.", true)
                        .enumOf("type", types, "Memory category.", false)
                        .build())
                .permission(PermissionLevel.ALLOW)
                .handler((a, ctx) -> {
                    MemoryEntry e = new MemoryEntry((String) a.get("name"), (String) a.get("description"),
                            MemoryType.parse((String) a.get("type")), (String) a.get("content"), LocalDate.now());
                    repo.save(e);
                    return ToolOutput.text("Saved memory '" + e.name() + "'.");
                }).build();
        Tool read = FunctionTool.builder("memory_read")
                .description("Read the full content of a saved memory.")
                .schema(Schemas.object().string("name", "The memory name from the index.", true).build())
                .readOnly(true)
                .handler((a, ctx) -> {
                    MemoryEntry e = repo.find((String) a.get("name"))
                            .orElseThrow(() -> new ToolException("No memory named '" + a.get("name") + "'."));
                    return ToolOutput.text("# " + e.name() + " [" + e.type().wire() + "]\n" + e.description() + "\n\n" + e.content());
                }).build();
        Tool search = FunctionTool.builder("memory_search")
                .description("Search saved memories by keywords.")
                .schema(Schemas.object().string("query", "Space-separated keywords.", true).build())
                .readOnly(true)
                .handler((a, ctx) -> {
                    List<MemoryEntry> hits = repo.search((String) a.get("query"), 10);
                    return ToolOutput.text(hits.isEmpty() ? "No matching memories." : hits.stream()
                            .map(e -> "- " + e.name() + " [" + e.type().wire() + "]: " + e.description())
                            .collect(Collectors.joining("\n")));
                }).build();
        Tool delete = FunctionTool.builder("memory_delete")
                .description("Delete a memory that is wrong or no longer relevant.")
                .schema(Schemas.object().string("name", "The memory name.", true).build())
                .permission(PermissionLevel.ALLOW)
                .handler((a, ctx) -> {
                    if (!repo.delete((String) a.get("name"))) {
                        throw new ToolException("No memory named '" + a.get("name") + "'.");
                    }
                    return ToolOutput.text("Deleted memory '" + a.get("name") + "'.");
                }).build();
        return List.of(save, read, search, delete);
    }
}
