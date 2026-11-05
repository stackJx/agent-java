package com.agentcore.domain.permission;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 权限规则（值对象），写法 {@code tool(pattern)}，例如 {@code bash(git status*)}、{@code write_file(/etc/*)}、
 * {@code mcp__github__*}。tool 与 pattern 都支持通配符；省略 pattern 表示匹配该工具的所有调用。
 */
public record PermissionRule(String tool, String pattern) {

    private static final Pattern SYNTAX = Pattern.compile("\\s*([^()\\s]+)\\s*(?:\\((.*)\\))?\\s*");

    public static PermissionRule parse(String text) {
        Matcher m = SYNTAX.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid permission rule: " + text);
        }
        String p = m.group(2);
        return new PermissionRule(m.group(1), p == null || p.isEmpty() || p.equals("*") ? null : p);
    }

    @Override
    public String toString() {
        return pattern == null ? tool : tool + "(" + pattern + ")";
    }
}
