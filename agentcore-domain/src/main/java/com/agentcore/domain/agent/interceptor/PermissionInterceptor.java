package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.domain.permission.service.PermissionPolicy;
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
    public ToolOutput intercept(ToolInvocation inv, Chain chain) {
        Tool tool = inv.tool();
        Map<String, Object> args = inv.arguments();
        PermissionPolicy.Verdict verdict = policy.check(tool, args);
        Decision decision = verdict.decision();
        String reason = verdict.reason();
        if (decision == Decision.ASK) {
            if (approver == null) {
                decision = Decision.DENY;
                reason = "this action requires user approval, but no approver is available (non-interactive run)";
            } else {
                PermissionRequest req = new PermissionRequest(UUID.randomUUID().toString(), inv.context().conversationId(),
                        tool.name(), args, tool.permissionSubject(args), tool.describe(args), reason);
                Approval approval;
                approvalLock.lock();
                try {
                    inv.context().cancel().throwIfCancelled();
                    approval = approver.approve(req);
                } finally {
                    approvalLock.unlock();
                }
                // 用户选了"总是允许"：交给策略记住（默认实现会生成一条 allow 规则，本进程内后续同类调用不再询问）
                if (approval.decision() == Decision.ALLOW && approval.remember()) {
                    String remembered = policy.remember(tool, args);
                    reason = remembered != null ? "always allowed (" + remembered + ")" : "approved by user";
                } else if (approval.message() != null && !approval.message().isBlank()) {
                    reason = approval.message();
                } else {
                    reason = approval.decision() == Decision.ALLOW ? "approved by user" : "denied by user";
                }
                decision = approval.decision() == Decision.ALLOW ? Decision.ALLOW : Decision.DENY;
            }
            inv.emit(new AgentEvent.PermissionDecided(inv.call().id(), tool.name(), decision.name().toLowerCase(), reason));
        }
        if (decision == Decision.DENY) {
            return ToolOutput.error("Permission denied: " + reason + ". Do not retry the same action; adjust your approach or ask the user.");
        }
        return chain.proceed(inv);
    }
}
