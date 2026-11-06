package com.agentcore.domain.tool.model;

import com.agentcore.domain.llm.model.ToolSpec;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具（领域概念）。实现可以是内置工具、注解方法、MCP 远端工具、子 Agent 等。
 */
public interface Tool {

    String name();

    String description();

    /** JSON Schema（type=object）。 */
    Map<String, Object> inputSchema();

    /** 执行。抛 {@link ToolException} 表示可让模型自行修正的错误。参数已按 schema 校验并做过温和的类型转换。 */
    ToolOutput execute(Map<String, Object> arguments, ToolContext context) throws Exception;

    /** 只读工具：默认免审批，且可与其他只读工具并行执行。 */
    default boolean readOnly() {
        return false;
    }

    default boolean concurrencySafe() {
        return readOnly();
    }

    default PermissionLevel defaultPermission() {
        return readOnly() ? PermissionLevel.ALLOW : PermissionLevel.ASK;
    }

    /** 单次执行超时；null 表示不限（工具自己控制）。 */
    default Duration timeout() {
        return Duration.ofMinutes(10);
    }

    default int maxOutputChars() {
        return 30_000;
    }

    /**
     * 这次调用要访问的文件路径（参数里的原始写法，例如 {@code src/../a.txt}）；不访问文件的工具返回 null（默认）。
     *
     * <p>返回非 null 时，默认权限策略按<b>路径</b>匹配规则：先把它和规则里的模式都解析成绝对路径
     * （去掉 . 和 ..、跟随符号链接）再比较，并且对工作目录之外的路径改为询问。见 {@code RulePermissionPolicy}。
     */
    default String permissionPath(Map<String, Object> arguments) {
        return null;
    }

    /** 权限规则匹配的对象（没有 {@link #permissionPath} 的工具用它）：bash 的命令、web_fetch 的 URL…… 也用于界面上的调用摘要。 */
    default String permissionSubject(Map<String, Object> arguments) {
        for (String key : new String[]{"command", "path", "file_path", "url", "pattern"}) {
            if (arguments.get(key) instanceof String s) {
                return s;
            }
        }
        return "";
    }

    /** 一行可读的调用摘要（UI 用）。 */
    default String describe(Map<String, Object> arguments) {
        String subject = permissionSubject(arguments);
        if (subject.isEmpty() && !arguments.isEmpty()) {
            subject = arguments.entrySet().stream().limit(3).map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
        }
        if (subject.length() > 120) {
            subject = subject.substring(0, 119) + "…";
        }
        return subject.isEmpty() ? name() : name() + "(" + subject + ")";
    }

    default ToolSpec spec() {
        return new ToolSpec(name(), description(), inputSchema());
    }
}
