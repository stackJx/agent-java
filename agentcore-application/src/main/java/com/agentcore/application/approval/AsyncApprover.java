package com.agentcore.application.approval;

import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.domain.shared.cancel.CancellationToken;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 异步审批（Web / IM 等非阻塞界面用）：需要审批时把请求推给该会话当前连接的前端通道，
 * 然后阻塞等待前端调用 {@link #resolve} 回传结果；超时、没有前端连接或运行被取消时拒绝。
 *
 * <p>与具体协议无关：SSE（{@code AgentController}）、WebSocket、钉钉/飞书卡片都可以作为通道。
 */
public final class AsyncApprover implements Approver {

    /** 前端通道：把审批请求推给用户。 */
    @FunctionalInterface
    public interface Channel {
        void requestApproval(PermissionRequest request);
    }

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();
    private final Duration timeout;

    private record PendingApproval(PermissionRequest request, CompletableFuture<Approval> future) {
    }

    public AsyncApprover(Duration timeout) {
        this.timeout = timeout;
    }

    public void attach(String conversationId, Channel channel) {
        channels.put(conversationId, channel);
    }

    /** 移除会话的通道。 */
    public void detach(String conversationId) {
        channels.remove(conversationId);
    }

    /** 只有当前挂着的正是 channel 时才移除：不会误删之后另一次运行挂上的通道。 */
    public void detach(String conversationId, Channel channel) {
        channels.remove(conversationId, channel);
    }

    @Override
    public Approval approve(PermissionRequest request) {
        return approve(request, CancellationToken.NONE);
    }

    /** 等待前端回传结果；运行被取消时立刻以拒绝结束等待，不必等到超时。 */
    @Override
    public Approval approve(PermissionRequest request, CancellationToken cancel) {
        Channel channel = channels.get(request.conversationId());
        if (channel == null) {
            return Approval.deny("no client is connected to approve this action");
        }
        CompletableFuture<Approval> answer = new CompletableFuture<>();
        pending.put(request.requestId(), new PendingApproval(request, answer));
        Runnable unregister = cancel.onCancel(() -> answer.complete(Approval.deny("cancelled")));
        try {
            channel.requestApproval(request);
            return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Approval.deny("approval timed out after " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Approval.deny("cancelled");
        } catch (Exception e) {
            return Approval.deny(String.valueOf(e.getMessage()));
        } finally {
            unregister.run();
            pending.remove(request.requestId());
        }
    }

    /** 前端回传审批结果；requestId 不存在（已超时或已处理）时返回 false。 */
    public boolean resolve(String requestId, Approval approval) {
        PendingApproval waiting = pending.get(requestId);
        return waiting != null && waiting.future().complete(approval);
    }

    /** 某个等待中的审批（已超时或已处理时为空）。接口层用它确认审批属于调用者的会话。 */
    public Optional<PermissionRequest> pendingRequest(String requestId) {
        return Optional.ofNullable(pending.get(requestId)).map(PendingApproval::request);
    }

    /** 当前等待中的审批（便于轮询式前端展示）。 */
    public List<PermissionRequest> pending(String conversationId) {
        return pending.values().stream().map(PendingApproval::request)
                .filter(request -> conversationId == null || request.conversationId().equals(conversationId)).toList();
    }
}
