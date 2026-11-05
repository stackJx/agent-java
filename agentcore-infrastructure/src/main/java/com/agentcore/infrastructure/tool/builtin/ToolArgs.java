package com.agentcore.infrastructure.tool.builtin;

import java.util.Map;

/** 内置工具读取参数的小工具：参数已经过 Schema 校验和类型转换，这里只处理"没传就用默认值"。 */
final class ToolArgs {

    private ToolArgs() {
    }

    static String string(Map<String, Object> args, String name, String fallback) {
        return args.get(name) instanceof String value ? value : fallback;
    }

    static int integer(Map<String, Object> args, String name, int fallback) {
        return args.get(name) instanceof Number value ? value.intValue() : fallback;
    }

    static boolean flag(Map<String, Object> args, String name) {
        return Boolean.TRUE.equals(args.get(name));
    }
}
