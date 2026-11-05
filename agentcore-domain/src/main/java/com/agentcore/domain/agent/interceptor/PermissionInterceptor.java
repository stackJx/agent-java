package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.domain.permission.service.PermissionPolicy;
import com.agentcore.domain.permission.service.PermissionPolicy.Verdict;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 关卡 4、5：权限策略判定；需要询问时交给审批人。没有审批人（无人值守）时一律拒绝。
 * 多个工具同时需要审批时排队，一次只问一个，避免多个提示交错。
 */
public final class PermissionInterceptor implements ToolInterceptor {

    private final PermissionPolicy policy;
    private final Approver approver;
    private final ReentrantLock approvalLock = new ReentrantLock();

    /** @param approver 可以为 null（无人值守）：需要询问的调用都会被拒绝 */
    public PermissionInterceptor(PermissionPolicy policy, Approver approver) {
        this.policy = policy;
        this.approver = approver;
    }

    @Override
    public ToolOutput intercept(ToolInvocation invocation, Chain chain) {
        // 4. 策略判定：允许 / 拒绝 / 要问用户
        Verdict verdict = policy.check(invocation.tool(), invocation.arguments());
        // 5. 要问用户：问完只剩允许或拒绝两种结论
        if (verdict.decision() == Decision.ASK) {
            verdict = askUser(invocation, verdict.reason());
            invocation.emit(new AgentEvent.PermissionDecided(invocation.call().id(), invocation.tool().name(),
                    verdict.decision().name().toLowerCase(), verdict.reason()));
        }
        if (verdict.decision() == Decision.DENY) {
            return ToolOutput.error("Permission denied: " + verdict.reason()
                    + ". Do not retry the same action; adjust your approach or ask the user.");
        }
        return chain.proceed(invocation);
    }

    private Verdict askUser(ToolInvocation invocation, String reason) {
        if (approver == null) {
            return Verdict.deny("this action requires user approval, but no approver is available (non-interactive run)");
        }
        Tool tool = invocation.tool();
        Map<String, Object> args = invocation.arguments();
        PermissionRequest request = new PermissionRequest(UUID.randomUUID().toString(), invocation.context().conversationId(),
                tool.name(), args, tool.permissionSubject(args), tool.describe(args), reason);
        Approval approval = approveOneAtATime(request, invocation);

        boolean allowed = approval.decision() == Decision.ALLOW;
        if (allowed && approval.remember()) {
            // 用户选了"总是允许"：交给策略记住（默认实现会生成一条 allow 规则，本进程内后续同类调用不再询问）
            String rule = policy.remember(tool, args);
            return Verdict.allow(rule != null ? "always allowed (" + rule + ")" : "approved by user");
        }
        String message = approval.message() != null && !approval.message().isBlank()
                ? approval.message()
                : allowed ? "approved by user" : "denied by user";
        return allowed ? Verdict.allow(message) : Verdict.deny(message);
    }

    private Approval approveOneAtATime(PermissionRequest request, ToolInvocation invocation) {
        approvalLock.lock();
        try {
            invocation.context().cancel().throwIfCancelled();
            return approver.approve(request);
        } finally {
            approvalLock.unlock();
        }
    }
}
