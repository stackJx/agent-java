package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.model.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内置工具集合。 */
public final class BuiltinTools {

    private BuiltinTools() {
    }

    public static Map<String, Tool> all() {
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : List.of(FileTools.readFile(), FileTools.writeFile(), FileTools.editFile(), SearchTools.glob(),
                SearchTools.grep(), ShellTools.bash(), WebTools.webFetch(), InteractionTools.todoWrite(), InteractionTools.askUser())) {
            byName.put(tool.name(), tool);
        }
        return byName;
    }

    /** 按名称选取；names 为 null 表示全部。 */
    public static List<Tool> select(List<String> names) {
        Map<String, Tool> all = all();
        if (names == null) {
            return new ArrayList<>(all.values());
        }
        List<Tool> selected = new ArrayList<>();
        for (String name : names) {
            if (all.containsKey(name)) {
                selected.add(all.get(name));
            }
        }
        return selected;
    }
}
