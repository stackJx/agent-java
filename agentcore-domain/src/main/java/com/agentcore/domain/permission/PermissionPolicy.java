package com.agentcore.domain.permission;

import com.agentcore.domain.shared.GlobPattern;
import com.agentcore.domain.tool.PermissionLevel;
import com.agentcore.domain.tool.Tool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 权限策略：判定每次工具调用是 允许 / 拒绝 / 询问。优先级 deny &gt; ask &gt; allow &gt; 模式默认值。
 *
 * <p>bash 命令按 {@code ; && || | 换行} 拆成子命令：deny 规则命中任一子命令即拒绝；
 * allow 要求每个子命令都被某条 allow 规则覆盖，且命令含 {@code $( ` > <} 时 allow 不生效（转为询问）。
 */
public final class PermissionPolicy {

    public record Verdict(Decision decision, String reason) {
    }

    private static final Pattern SPLIT = Pattern.compile("\\s*(?:;|&&|\\|\\||\\||\\n)\\s*");
    private static final Pattern RISKY = Pattern.compile("\\$\\(|`|>|<");

    private final PermissionMode mode;
    private final List<PermissionRule> allow;
    private final List<PermissionRule> deny;
    private final List<PermissionRule> ask;

    public PermissionPolicy(PermissionMode mode, List<PermissionRule> allow, List<PermissionRule> deny, List<PermissionRule> ask) {
        this.mode = mode == null ? PermissionMode.DEFAULT : mode;
        this.allow = new CopyOnWriteArrayList<>(allow == null ? List.of() : allow);
        this.deny = List.copyOf(deny == null ? List.of() : deny);
        this.ask = List.copyOf(ask == null ? List.of() : ask);
    }

    public static PermissionPolicy defaults() {
        return new PermissionPolicy(PermissionMode.DEFAULT, List.of(), List.of(), List.of());
    }

    public static PermissionPolicy of(String mode, List<String> allow, List<String> deny, List<String> ask) {
        return new PermissionPolicy(PermissionMode.parse(mode), parseAll(allow), parseAll(deny), parseAll(ask));
    }

    private static List<PermissionRule> parseAll(List<String> rules) {
        return rules == null ? List.of() : rules.stream().map(PermissionRule::parse).toList();
    }

    public PermissionMode mode() {
        return mode;
    }

    public Verdict check(Tool tool, Map<String, Object> arguments) {
        String subject = tool.permissionSubject(arguments);
        for (PermissionRule r : deny) {
            if (matchesAny(r, tool.name(), subject)) {
                return new Verdict(Decision.DENY, "denied by rule " + r);
            }
        }
        for (PermissionRule r : ask) {
            if (matchesAny(r, tool.name(), subject)) {
                return new Verdict(Decision.ASK, "rule " + r + " requires approval");
            }
        }
        PermissionRule hit = allowedBy(tool.name(), subject);
        if (hit != null) {
            return new Verdict(Decision.ALLOW, "allowed by rule " + hit);
        }
        if (mode == PermissionMode.AUTO) {
            return new Verdict(Decision.ALLOW, "auto mode");
        }
        boolean defaultAllow = tool.defaultPermission() == PermissionLevel.ALLOW;
        if (mode == PermissionMode.READONLY && !defaultAllow) {
            return new Verdict(Decision.DENY, "read-only mode: this tool can modify state");
        }
        return new Verdict(defaultAllow ? Decision.ALLOW : Decision.ASK, "tool default");
    }

    /** 用户选择「总是允许」时，生成一条尽量具体的规则加入 allow 列表。 */
    public PermissionRule remember(Tool tool, Map<String, Object> arguments) {
        PermissionRule rule;
        if (tool.name().equals("bash")) {
            String cmd = tool.permissionSubject(arguments).strip();
            String first = cmd.isEmpty() ? "" : cmd.split("\\s+")[0];
            rule = first.isEmpty() ? new PermissionRule("bash", null) : new PermissionRule("bash", first + " *");
        } else {
            rule = new PermissionRule(tool.name(), null);
        }
        if (!allow.contains(rule)) {
            allow.add(rule);
        }
        return rule;
    }

    public static List<String> splitCommands(String command) {
        return Arrays.stream(SPLIT.split(command.strip())).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean matchesAny(PermissionRule r, String toolName, String subject) {
        if (!GlobPattern.matches(r.tool(), toolName)) {
            return false;
        }
        if (r.pattern() == null) {
            return true;
        }
        if (toolName.equals("bash")) {
            return GlobPattern.matches(r.pattern(), subject)
                    || splitCommands(subject).stream().anyMatch(p -> GlobPattern.matches(r.pattern(), p));
        }
        return GlobPattern.matches(r.pattern(), subject);
    }

    private PermissionRule allowedBy(String toolName, String subject) {
        List<PermissionRule> rules = new ArrayList<>();
        for (PermissionRule r : allow) {
            if (GlobPattern.matches(r.tool(), toolName)) {
                if (r.pattern() == null) {
                    return r;
                }
                rules.add(r);
            }
        }
        if (rules.isEmpty()) {
            return null;
        }
        if (!toolName.equals("bash")) {
            return rules.stream().filter(r -> GlobPattern.matches(r.pattern(), subject)).findFirst().orElse(null);
        }
        List<String> parts = splitCommands(subject);
        if (parts.isEmpty() || RISKY.matcher(subject).find()) {
            return null;
        }
        PermissionRule first = null;
        for (String part : parts) {
            PermissionRule hit = rules.stream().filter(r -> GlobPattern.matches(r.pattern(), part)).findFirst().orElse(null);
            if (hit == null) {
                return null;
            }
            first = first == null ? hit : first;
        }
        return first;
    }
}
