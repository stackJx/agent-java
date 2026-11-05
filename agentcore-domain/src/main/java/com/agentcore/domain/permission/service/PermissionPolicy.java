package com.agentcore.domain.permission.service;

import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.tool.model.Tool;
import java.util.Map;

/**
 * 权限策略：判定每次工具调用是 允许 / 拒绝 / 询问用户。引擎在参数校验通过之后、执行之前调用它。
 *
 * <p>默认实现是基于配置规则的 {@link RulePermissionPolicy}。需要按租户、角色或对接公司权限系统判断时，
 * 实现这个接口即可（在 Spring Boot 里定义一个 PermissionPolicy 类型的 Bean，就会替换掉默认实现）：
 * <pre>{@code
 * PermissionPolicy policy = (tool, args) -> tool.readOnly()
 *         ? PermissionPolicy.Verdict.allow("read-only tool")
 *         : PermissionPolicy.Verdict.ask("needs approval");
 * }</pre>
 * 实现必须是线程安全的：并行执行的工具会同时调用 {@link #check}。
 */
@FunctionalInterface
public interface PermissionPolicy {

    /** 判定结果。reason 会出现在审批提示、事件和拒绝时交给模型的错误信息里。 */
    record Verdict(Decision decision, String reason) {

        public static Verdict allow(String reason) {
            return new Verdict(Decision.ALLOW, reason);
        }

        public static Verdict ask(String reason) {
            return new Verdict(Decision.ASK, reason);
        }

        public static Verdict deny(String reason) {
            return new Verdict(Decision.DENY, reason);
        }
    }

    /** 判定一次工具调用。arguments 已通过 Schema 校验。 */
    Verdict check(Tool tool, Map<String, Object> arguments);

    /**
     * 用户审批时选了"总是允许"。能记住的实现在这里记下来，之后同类调用直接放行，并返回一段描述（出现在事件里）；
     * 默认不记住：返回 null，这次放行，下次照样询问。
     */
    default String remember(Tool tool, Map<String, Object> arguments) {
        return null;
    }

    /** 默认策略：没有任何规则，只读工具放行，其他询问。 */
    static PermissionPolicy defaults() {
        return RulePermissionPolicy.defaults();
    }
}
