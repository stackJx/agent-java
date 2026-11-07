package com.agentcore.domain.permission.model;

import java.util.Map;

/**
 * 需要用户审批的一次工具调用。requestId 唯一，供异步审批（例如 Web 界面）回传。
 *
 * @param callId 对应的工具调用 ID（界面据此把审批显示在那张工具卡片上）；不知道时为 null
 */
public record PermissionRequest(String requestId, String conversationId, String tool, Map<String, Object> arguments,
                                String subject, String summary, String reason, String callId) {

    public PermissionRequest(String requestId, String conversationId, String tool, Map<String, Object> arguments,
                             String subject, String summary, String reason) {
        this(requestId, conversationId, tool, arguments, subject, summary, reason, null);
    }
}
