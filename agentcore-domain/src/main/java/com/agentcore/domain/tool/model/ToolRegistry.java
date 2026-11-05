package com.agentcore.domain.tool.model;

import com.agentcore.domain.llm.model.ToolSpec;
import com.agentcore.domain.shared.util.GlobPattern;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 工具注册表。保持注册顺序稳定 —— 工具列表是 prompt cache 前缀的一部分。 */
public final class ToolRegistry implements Iterable<Tool> {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry() {
    }

    public ToolRegistry(Collection<? extends Tool> initial) {
        initial.forEach(this::register);
    }

    public synchronized ToolRegistry register(Tool tool) {
        if (tool.name() == null || !tool.name().matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid tool name: " + tool.name());
        }
        tools.put(tool.name(), tool);
        return this;
    }

    public synchronized ToolRegistry registerAll(Collection<? extends Tool> list) {
        list.forEach(this::register);
        return this;
    }

    public synchronized void unregister(String name) {
        tools.remove(name);
    }

    public synchronized Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    public synchronized List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public synchronized List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    public synchronized int size() {
        return tools.size();
    }

    /** 按名称通配符筛选出新注册表（子 Agent 用）。include 为 null 表示全部。 */
    public synchronized ToolRegistry filtered(List<String> include, List<String> exclude) {
        ToolRegistry out = new ToolRegistry();
        for (Tool t : tools.values()) {
            if (include != null && include.stream().noneMatch(p -> GlobPattern.matches(p, t.name()))) {
                continue;
            }
            if (exclude != null && exclude.stream().anyMatch(p -> GlobPattern.matches(p, t.name()))) {
                continue;
            }
            out.register(t);
        }
        return out;
    }

    @Override
    public synchronized Iterator<Tool> iterator() {
        return List.copyOf(tools.values()).iterator();
    }
}
