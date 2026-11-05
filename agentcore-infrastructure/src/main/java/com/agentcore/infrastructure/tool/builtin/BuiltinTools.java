package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内置工具集合。 */
public final class BuiltinTools {

    private BuiltinTools() {
    }

    public static Map<String, Tool> all() {
        Map<String, Tool> m = new LinkedHashMap<>();
        for (Tool t : List.of(FileTools.readFile(), FileTools.writeFile(), FileTools.editFile(), FileTools.glob(),
                FileTools.grep(), ShellTools.bash(), WebTools.webFetch(), InteractionTools.todoWrite(), InteractionTools.askUser())) {
            m.put(t.name(), t);
        }
        return m;
    }

    /** 按名称选取；names 为 null 表示全部。 */
    public static List<Tool> select(List<String> names) {
        Map<String, Tool> all = all();
        if (names == null) {
            return new ArrayList<>(all.values());
        }
        List<Tool> out = new ArrayList<>();
        for (String n : names) {
            if (all.containsKey(n)) {
                out.add(all.get(n));
            }
        }
        return out;
    }
}
