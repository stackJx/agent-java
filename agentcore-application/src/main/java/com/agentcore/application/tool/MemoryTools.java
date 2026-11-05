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

    private static final int MAX_SEARCH_HITS = 10;

    private MemoryTools() {
    }

    public static List<Tool> create(MemoryRepository repository) {
        return List.of(save(repository), read(repository), search(repository), delete(repository));
    }

    private static Tool save(MemoryRepository repository) {
        List<String> types = Arrays.stream(MemoryType.values()).map(MemoryType::wire).toList();
        return FunctionTool.builder("memory_save")
                .description("Save (create or overwrite) a long-term memory that persists across sessions.")
                .schema(Schemas.object()
                        .string("name", "Short kebab-case identifier, e.g. \"user-prefers-concise-answers\".", true)
                        .string("description", "One-line summary used to decide relevance later.", true)
                        .string("content", "The memory itself. For feedback/project memories, include why it matters and how to apply it.", true)
                        .enumOf("type", types, "Memory category.", false)
                        .build())
                .permission(PermissionLevel.ALLOW)
                .handler((args, ctx) -> {
                    MemoryEntry entry = new MemoryEntry((String) args.get("name"), (String) args.get("description"),
                            MemoryType.parse((String) args.get("type")), (String) args.get("content"), LocalDate.now());
                    repository.save(entry);
                    return ToolOutput.text("Saved memory '" + entry.name() + "'.");
                }).build();
    }

    private static Tool read(MemoryRepository repository) {
        return FunctionTool.builder("memory_read")
                .description("Read the full content of a saved memory.")
                .schema(Schemas.object().string("name", "The memory name from the index.", true).build())
                .readOnly(true)
                .handler((args, ctx) -> {
                    MemoryEntry entry = repository.find((String) args.get("name"))
                            .orElseThrow(() -> new ToolException("No memory named '" + args.get("name") + "'."));
                    return ToolOutput.text("# " + entry.name() + " [" + entry.type().wire() + "]\n" + entry.description()
                            + "\n\n" + entry.content());
                }).build();
    }

    private static Tool search(MemoryRepository repository) {
        return FunctionTool.builder("memory_search")
                .description("Search saved memories by keywords.")
                .schema(Schemas.object().string("query", "Space-separated keywords.", true).build())
                .readOnly(true)
                .handler((args, ctx) -> {
                    List<MemoryEntry> hits = repository.search((String) args.get("query"), MAX_SEARCH_HITS);
                    return ToolOutput.text(hits.isEmpty() ? "No matching memories." : hits.stream()
                            .map(entry -> "- " + entry.name() + " [" + entry.type().wire() + "]: " + entry.description())
                            .collect(Collectors.joining("\n")));
                }).build();
    }

    private static Tool delete(MemoryRepository repository) {
        return FunctionTool.builder("memory_delete")
                .description("Delete a memory that is wrong or no longer relevant.")
                .schema(Schemas.object().string("name", "The memory name.", true).build())
                .permission(PermissionLevel.ALLOW)
                .handler((args, ctx) -> {
                    if (!repository.delete((String) args.get("name"))) {
                        throw new ToolException("No memory named '" + args.get("name") + "'.");
                    }
                    return ToolOutput.text("Deleted memory '" + args.get("name") + "'.");
                }).build();
    }
}
