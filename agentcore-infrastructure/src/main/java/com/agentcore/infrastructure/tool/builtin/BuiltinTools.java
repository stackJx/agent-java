package com.agentcore.infrastructure.tool.builtin;

import com.agentcore.domain.tool.model.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内置工具集合。 */
public final class BuiltinTools {

    /** 内置工具的可调选项。 */
    public record Options(boolean webFetchAllowsPrivateNetwork) {
        /** 默认：web_fetch 不允许访问本机和内网地址。 */
        public static Options defaults() {
            return new Options(false);
        }
    }

    private BuiltinTools() {
    }

    public static Map<String, Tool> all() {
        return all(Options.defaults());
    }

    public static Map<String, Tool> all(Options options) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : List.of(FileTools.readFile(), FileTools.writeFile(), FileTools.editFile(), SearchTools.glob(),
                SearchTools.grep(), ShellTools.bash(), WebTools.webFetch(options.webFetchAllowsPrivateNetwork()),
                InteractionTools.todoWrite(), InteractionTools.askUser())) {
            byName.put(tool.name(), tool);
        }
        return byName;
    }

    /** 按名称选取；names 为 null 表示全部。 */
    public static List<Tool> select(List<String> names) {
        return select(names, Options.defaults());
    }

    /** 按名称选取；names 为 null 表示全部。 */
    public static List<Tool> select(List<String> names, Options options) {
        Map<String, Tool> all = all(options);
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
