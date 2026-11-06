package com.agentcore.domain.permission.gateway;

import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.domain.shared.cancel.CancellationToken;

/** 审批端口：由宿主实现（命令行询问、Web 弹窗、IM 审批流……）。可以阻塞直到用户作出决定。 */
@FunctionalInterface
public interface Approver {

    Approval approve(PermissionRequest request);

    /**
     * 可取消的审批，引擎调用的是这个：运行被取消时，实现应该尽快返回（结果无所谓，引擎随后会按取消处理）。
     * 默认忽略取消令牌，适合同步的命令行审批（在提示下按 Ctrl+C 就是拒绝）；异步审批应当覆盖它。
     */
    default Approval approve(PermissionRequest request, CancellationToken cancel) {
        return approve(request);
    }
}
