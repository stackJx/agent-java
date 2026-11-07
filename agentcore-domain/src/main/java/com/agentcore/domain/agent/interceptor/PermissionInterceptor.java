package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.domain.permission.service.PermissionPolicy;
import com.agentcore.domain.permission.service.PermissionPolicy.Verdict;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolContext;
import com.agentcore.domain.tool.model.ToolOutput;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 关卡 4、5：权限策略判定；需要询问时交给审批人。没有审批人（无人值守）时一律拒绝。
 *
 * <p>同一个会话里多个工具同时需要审批时排队，一次只问一个，避免多个提示交错；
 * 不同会话之间互不影响（引擎是所有会话共用的，所以队列按会话 ID 分开）。
 * 排队和等待审批时都可以被取消。
 */
public final class PermissionInterceptor implements ToolInterceptor {

    private final PermissionPolicy policy;
    private final Approver approver;
    /** 会话 ID → 这个会话的审批队列。没人排队时移除，所以表的大小只和"正在等审批的会话"有关。 */
    private final Map<String, ApprovalQueue> queues = new ConcurrentHashMap<>();

    /** 一个会话的审批队列：一把锁，加上正在使用它的调用数（为 0 时从表里移除）。 */
    private static final class ApprovalQueue {
        private final ReentrantLock lock = new ReentrantLock();
        /** 只在 ConcurrentHashMap.compute 里读写，同一个键的 compute 是互斥的。 */
        private int users;
    }

    /** @param approver 可以为 null（无人值守）：需要询问的调用都会被拒绝 */
    public PermissionInterceptor(PermissionPolicy policy, Approver approver) {
        this.policy = policy;
        this.approver = approver;
    }

    @Override
    public ToolOutput intercept(ToolInvocation invocation, Chain chain) {
        // 4. 策略判定：允许 / 拒绝 / 要问用户
        Verdict verdict = policy.check(invocation.tool(), invocation.arguments(), invocation.context());
        // 5. 要问用户：问完只剩允许或拒绝两种结论
        if (verdict.decision() == Decision.ASK) {
            long askedAt = System.nanoTime();
            verdict = askUser(invocation, verdict.reason());
            long waitMillis = (System.nanoTime() - askedAt) / 1_000_000;
            invocation.emit(new AgentEvent.PermissionDecided(invocation.call().id(), invocation.tool().name(),
                    verdict.decision().name().toLowerCase(), verdict.reason(), waitMillis));
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
        ToolContext context = invocation.context();
        PermissionRequest request = new PermissionRequest(UUID.randomUUID().toString(), context.conversationId(),
                tool.name(), args, tool.permissionSubject(args), tool.describe(args), reason, invocation.call().id());
        Approval approval = approveOneAtATime(request, context.cancel());

        boolean allowed = approval.decision() == Decision.ALLOW;
        if (allowed && approval.remember()) {
            // 用户选了"总是允许"：交给策略记住（默认实现为当前会话生成 allow 规则，之后同类调用不再询问）
            String rule = policy.remember(tool, args, context);
            return Verdict.allow(rule != null ? "always allowed (" + rule + ")" : "approved by user");
        }
        String message = approval.message() != null && !approval.message().isBlank()
                ? approval.message()
                : allowed ? "approved by user" : "denied by user";
        return allowed ? Verdict.allow(message) : Verdict.deny(message);
    }

    /** 按会话排队审批。排队时被取消（执行线程被中断）或审批过程中被取消，都按取消处理，绝不当作允许。 */
    private Approval approveOneAtATime(PermissionRequest request, CancellationToken cancel) {
        String conversationId = String.valueOf(request.conversationId());
        ApprovalQueue queue = joinQueue(conversationId);
        try {
            queue.lock.lockInterruptibly();
            try {
                cancel.throwIfCancelled();
                Approval approval = approver.approve(request, cancel);
                cancel.throwIfCancelled();
                return approval;
            } finally {
                queue.lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } finally {
            leaveQueue(conversationId);
        }
    }

    private ApprovalQueue joinQueue(String conversationId) {
        return queues.compute(conversationId, (id, existing) -> {
            ApprovalQueue queue = existing != null ? existing : new ApprovalQueue();
            queue.users++;
            return queue;
        });
    }

    private void leaveQueue(String conversationId) {
        queues.computeIfPresent(conversationId, (id, queue) -> --queue.users == 0 ? null : queue);
    }
}
