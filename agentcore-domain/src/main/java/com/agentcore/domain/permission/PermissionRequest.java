package com.agentcore.domain.permission;

import java.util.Map;

/** 需要用户审批的一次工具调用。requestId 唯一，供异步审批（例如 Web 界面）回传。 */
public record PermissionRequest(String requestId, String conversationId, String tool, Map<String, Object> arguments,
                                String subject, String summary, String reason) {
}
