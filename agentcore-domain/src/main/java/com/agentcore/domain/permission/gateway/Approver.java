package com.agentcore.domain.permission.gateway;

import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.PermissionRequest;

/** 审批端口：由宿主实现（命令行询问、Web 弹窗、IM 审批流……）。可以阻塞直到用户作出决定。 */
@FunctionalInterface
public interface Approver {
    Approval approve(PermissionRequest request);
}
