package com.agentcore.domain.permission.service;

import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.permission.model.PermissionMode;
import com.agentcore.domain.permission.model.PermissionRule;
import com.agentcore.domain.shared.util.BoundedLruCache;
import com.agentcore.domain.shared.util.FilePaths;
import com.agentcore.domain.shared.util.GlobPattern;
import com.agentcore.domain.tool.model.PermissionLevel;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 默认的权限策略：基于 allow / ask / deny 规则和权限模式判定。优先级 deny &gt; ask &gt; allow &gt; 模式默认值。
 * 规则来自配置 {@code agentcore.permissions.*}；用户审批时选"总是允许"，会为<b>当前会话</b>追加 allow 规则（只在当前进程有效）。
 *
 * <p>bash 命令按 {@code ; && || | & 换行} 拆成子命令：deny 规则命中任一子命令即拒绝；
 * allow 要求每个子命令都被某条 allow 规则覆盖，且命令含 {@code $( ` > <} 时 allow 不生效（转为询问）。
 *
 * <p>文件工具（声明了 {@link Tool#permissionPath} 的）按<b>路径</b>匹配：调用里的路径和规则里的模式都先解析成绝对路径
 * （去掉 . 和 ..、跟随符号链接，见 {@link FilePathRules}），所以 {@code src/../../etc/passwd} 匹配不上 {@code write_file(src/*)}，
 * 却躲不过 {@code write_file(/etc/*)}。没有规则命中时，默认放行的工具访问工作目录之外的路径改为询问。
 *
 * <p><b>deny 规则只是按字符串匹配</b>：{@code rm  -rf}（两个空格）、{@code /bin/rm -rf}、{@code sh -c '…'} 都能绕开，
 * 只能用来提醒模型"别这么做"，不能当作安全边界。真正的边界是 allow 白名单 + 人工审批。
 *
 * <p>需要按租户、角色或公司权限系统判断时，可以自己实现 {@link PermissionPolicy}，
 * 也可以包一层：先调用本类的 {@link #check}，再叠加自己的规则。
 */
public final class RulePermissionPolicy implements PermissionPolicy {

    /** bash 子命令的分隔符。顺序要紧：先试两个字符的 {@code && || |&}，再试单个的 {@code | &}。 */
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:;|&&|\\|\\||\\|&|\\||&|\\n)\\s*");
    private static final Pattern RISKY = Pattern.compile("\\$\\(|`|>|<");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** 最多为多少个会话保存"总是允许"记下的规则；更早的会话的规则会被丢掉（下次重新询问）。 */
    private static final int MAX_CONVERSATIONS_WITH_REMEMBERED_RULES = 1000;

    private final PermissionMode mode;
    /** 配置里的 allow 规则，加上不带会话上下文时"总是允许"记下的规则。 */
    private final List<PermissionRule> allow;
    private final List<PermissionRule> deny;
    private final List<PermissionRule> ask;
    /** 会话 ID → 这个会话里"总是允许"记下的规则。 */
    private final BoundedLruCache<String, List<PermissionRule>> rememberedByConversation =
            new BoundedLruCache<>(MAX_CONVERSATIONS_WITH_REMEMBERED_RULES);

    public RulePermissionPolicy(PermissionMode mode, List<PermissionRule> allow, List<PermissionRule> deny, List<PermissionRule> ask) {
        this.mode = mode == null ? PermissionMode.DEFAULT : mode;
        this.allow = new CopyOnWriteArrayList<>(allow == null ? List.of() : allow);
        this.deny = List.copyOf(deny == null ? List.of() : deny);
        this.ask = List.copyOf(ask == null ? List.of() : ask);
    }

    /** 没有任何规则、default 模式：只读工具放行，其他询问。 */
    public static RulePermissionPolicy defaults() {
        return new RulePermissionPolicy(PermissionMode.DEFAULT, List.of(), List.of(), List.of());
    }

    /** 从配置的字符串创建，例如 {@code of("default", List.of("bash(git status*)"), List.of("bash(rm -rf *)"), null)}。 */
    public static RulePermissionPolicy of(String mode, List<String> allow, List<String> deny, List<String> ask) {
        return new RulePermissionPolicy(PermissionMode.parse(mode), parseAll(allow), parseAll(deny), parseAll(ask));
    }

    private static List<PermissionRule> parseAll(List<String> rules) {
        return rules == null ? List.of() : rules.stream().map(PermissionRule::parse).toList();
    }

    public PermissionMode mode() {
        return mode;
    }

    // ------------------------------------------------------------------ 判定

    @Override
    public Verdict check(Tool tool, Map<String, Object> arguments) {
        return check(tool, arguments, null);
    }

    /**
     * context 为 null 时：不看会话里记下的规则；文件路径只去掉 . 和 ..（不知道工作目录，没法补全成绝对路径），
     * 也不做"工作目录之外要询问"的判断。
     */
    @Override
    public Verdict check(Tool tool, Map<String, Object> arguments, ToolContext context) {
        CallSubject subject = CallSubject.of(tool, arguments, context);
        for (PermissionRule rule : deny) {
            if (matchesAny(rule, subject)) {
                return new Verdict(Decision.DENY, "denied by rule " + rule);
            }
        }
        for (PermissionRule rule : ask) {
            if (matchesAny(rule, subject)) {
                return new Verdict(Decision.ASK, "rule " + rule + " requires approval");
            }
        }
        PermissionRule hit = allowedBy(allowRulesFor(context), subject);
        if (hit != null) {
            return new Verdict(Decision.ALLOW, "allowed by rule " + hit);
        }
        if (mode == PermissionMode.AUTO) {
            return new Verdict(Decision.ALLOW, "auto mode");
        }
        if (mode == PermissionMode.READONLY && !tool.readOnly()) {
            return new Verdict(Decision.DENY, "read-only mode: this tool can modify state");
        }
        return toolDefault(tool, subject);
    }

    /** 没有规则命中时：按工具的默认权限；默认放行的工具访问工作目录之外的路径，改为询问。 */
    private static Verdict toolDefault(Tool tool, CallSubject subject) {
        if (tool.defaultPermission() != PermissionLevel.ALLOW) {
            return new Verdict(Decision.ASK, "tool default");
        }
        if (subject.isOutsideWorkingDirectory()) {
            return new Verdict(Decision.ASK, "the path is outside the working directory");
        }
        return new Verdict(Decision.ALLOW, "tool default");
    }

    /** 配置的 allow 规则 + 这个会话里"总是允许"记下的规则。 */
    private List<PermissionRule> allowRulesFor(ToolContext context) {
        List<PermissionRule> remembered = context == null ? null : rememberedByConversation.get(context.conversationId());
        if (remembered == null || remembered.isEmpty()) {
            return allow;
        }
        List<PermissionRule> combined = new ArrayList<>(allow);
        combined.addAll(remembered);
        return combined;
    }

    // ------------------------------------------------------------------ 总是允许

    /** 不知道是哪个会话：规则加进全局 allow 列表，对之后所有会话生效。 */
    @Override
    public String remember(Tool tool, Map<String, Object> arguments) {
        return describe(addRules(allow, rememberRules(tool, arguments)));
    }

    /** 引擎调用的版本：规则只对当前会话生效。 */
    @Override
    public String remember(Tool tool, Map<String, Object> arguments, ToolContext context) {
        if (context == null || context.conversationId() == null) {
            return remember(tool, arguments);
        }
        List<PermissionRule> conversationRules =
                rememberedByConversation.computeIfAbsent(context.conversationId(), id -> new CopyOnWriteArrayList<>());
        return describe(addRules(conversationRules, rememberRules(tool, arguments)));
    }

    /**
     * 用户选择「总是允许」时生成的规则，尽量具体：
     * <ul>
     *   <li>bash：每个子命令一条。{@code git status -s} → {@code bash(git status*)}（命令 + 子命令，参数随意）；
     *       第二个词是选项时只放行完全相同的子命令，例如 {@code rm -rf build} → {@code bash(rm -rf build)}；</li>
     *   <li>其他工具：放行这个工具的所有调用。</li>
     * </ul>
     */
    List<PermissionRule> rememberRules(Tool tool, Map<String, Object> arguments) {
        if (!tool.name().equals("bash")) {
            return List.of(new PermissionRule(tool.name(), null));
        }
        List<String> commands = splitCommands(tool.permissionSubject(arguments));
        if (commands.isEmpty()) {
            return List.of(new PermissionRule("bash", null));
        }
        return commands.stream().map(RulePermissionPolicy::ruleForCommand).distinct().toList();
    }

    private static PermissionRule ruleForCommand(String command) {
        String[] words = WHITESPACE.split(command.strip());
        boolean hasSubcommand = words.length >= 2 && !words[1].startsWith("-");
        String pattern = hasSubcommand ? words[0] + " " + words[1] + "*" : command.strip();
        return new PermissionRule("bash", pattern);
    }

    private static List<PermissionRule> addRules(List<PermissionRule> target, List<PermissionRule> rules) {
        for (PermissionRule rule : rules) {
            if (!target.contains(rule)) {
                target.add(rule);
            }
        }
        return rules;
    }

    private static String describe(List<PermissionRule> rules) {
        return rules.stream().map(PermissionRule::toString).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ 规则匹配

    /**
     * 规则要匹配的对象。
     *
     * @param text             用来匹配的文字：文件工具是解析后的路径（分隔符为 /），其他工具是 permissionSubject（bash 是命令）
     * @param resolvedPath     文件工具解析后的路径；不是文件工具（或路径不合法）时为 null
     * @param workingDirectory 本次运行的工作目录；没有调用上下文时为 null
     */
    private record CallSubject(String toolName, String text, Path resolvedPath, Path workingDirectory) {

        static CallSubject of(Tool tool, Map<String, Object> arguments, ToolContext context) {
            Path workingDirectory = context == null ? null : context.cwd();
            String rawPath = tool.permissionPath(arguments);
            Path resolved = rawPath == null ? null : FilePathRules.resolveCallPath(rawPath, workingDirectory);
            if (resolved == null) {
                String text = rawPath != null ? rawPath : tool.permissionSubject(arguments);
                return new CallSubject(tool.name(), text, null, workingDirectory);
            }
            return new CallSubject(tool.name(), FilePaths.withForwardSlashes(resolved), resolved, workingDirectory);
        }

        boolean isBash() {
            return toolName.equals("bash");
        }

        boolean isPath() {
            return resolvedPath != null;
        }

        boolean isOutsideWorkingDirectory() {
            return isPath() && workingDirectory != null && !FilePathRules.isInside(resolvedPath, workingDirectory);
        }

        /** 规则的模式能否匹配这段文字。文件路径：模式先换成和路径同一种形式（见 {@link FilePathRules}）。 */
        boolean matches(String rulePattern, String candidate) {
            String pattern = isPath() ? FilePathRules.resolvePattern(rulePattern, workingDirectory) : rulePattern;
            return GlobPattern.matches(pattern, candidate);
        }
    }

    public static List<String> splitCommands(String command) {
        return Arrays.stream(SPLIT.split(command.strip())).filter(s -> !s.isEmpty()).toList();
    }

    /** deny / ask 规则：bash 命中整条命令或任意一个子命令就算命中。 */
    private static boolean matchesAny(PermissionRule rule, CallSubject subject) {
        if (!GlobPattern.matches(rule.tool(), subject.toolName())) {
            return false;
        }
        if (rule.pattern() == null) {
            return true;
        }
        if (subject.isBash()) {
            return subject.matches(rule.pattern(), subject.text())
                    || splitCommands(subject.text()).stream().anyMatch(part -> subject.matches(rule.pattern(), part));
        }
        return subject.matches(rule.pattern(), subject.text());
    }

    /** allow 规则：bash 要求每个子命令都被某条规则覆盖，且命令里没有命令替换、重定向。返回第一条命中的规则。 */
    private static PermissionRule allowedBy(List<PermissionRule> allowRules, CallSubject subject) {
        List<PermissionRule> rules = new ArrayList<>();
        for (PermissionRule rule : allowRules) {
            if (GlobPattern.matches(rule.tool(), subject.toolName())) {
                if (rule.pattern() == null) {
                    return rule;
                }
                rules.add(rule);
            }
        }
        if (rules.isEmpty()) {
            return null;
        }
        if (!subject.isBash()) {
            return firstMatching(rules, subject, subject.text());
        }
        List<String> parts = splitCommands(subject.text());
        if (parts.isEmpty() || RISKY.matcher(subject.text()).find()) {
            return null;
        }
        PermissionRule first = null;
        for (String part : parts) {
            PermissionRule hit = firstMatching(rules, subject, part);
            if (hit == null) {
                return null;
            }
            first = first == null ? hit : first;
        }
        return first;
    }

    private static PermissionRule firstMatching(List<PermissionRule> rules, CallSubject subject, String candidate) {
        return rules.stream().filter(rule -> subject.matches(rule.pattern(), candidate)).findFirst().orElse(null);
    }
}
