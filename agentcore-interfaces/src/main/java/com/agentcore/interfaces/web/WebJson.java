package com.agentcore.interfaces.web;

import com.agentcore.domain.permission.Approval;
import com.agentcore.domain.permission.Decision;
import com.agentcore.domain.permission.PermissionRequest;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Web 接口的 JSON 约定：审批请求、审批结果的格式（控制器与 SSE 推送共用）。 */
public final class WebJson {

    private WebJson() {
    }

    public static ObjectNode approvalRequired(PermissionRequest req) {
        ObjectNode data = Json.object().put("request_id", req.requestId()).put("tool", req.tool())
                .put("summary", req.summary()).put("subject", req.subject()).put("reason", req.reason());
        data.set("arguments", Json.tree(req.arguments()));
        return data;
    }

    /** {"decision": "allow|always|deny", "message": "..."} → Approval */
    public static Approval approval(JsonNode body) {
        return switch (body.path("decision").asText("deny")) {
            case "allow" -> Approval.allow();
            case "always" -> Approval.always();
            default -> new Approval(Decision.DENY, false, body.path("message").asText(""));
        };
    }
}
