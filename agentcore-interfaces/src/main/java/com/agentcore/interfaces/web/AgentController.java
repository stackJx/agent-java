package com.agentcore.interfaces.web;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.agent.event.AgentEvent;
import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.ImageBlock;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.permission.model.Approval;
import com.agentcore.domain.permission.model.Decision;
import com.agentcore.domain.permission.model.PermissionRequest;
import com.agentcore.infrastructure.json.DomainCodec;
import com.agentcore.infrastructure.json.EventCodec;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对外的 HTTP + SSE 接口（Spring MVC），路径前缀 {@code agentcore.web.base-path}（默认 /api/agent）。
 * 独立部署版（{@code bin/agentcore serve}）和嵌入 Spring Boot 应用时用的都是它。
 * JSON 由 agentcore 自己序列化（{@code DomainCodec}、{@code EventCodec}），不依赖应用的 Jackson 版本。
 *
 * <pre>
 * GET  {base}/health                           健康检查（配置了 token 也不需要鉴权）
 * GET  {base}/conversations                    会话列表
 * POST {base}/conversations                    新建会话
 * GET  {base}/conversations/{id}               会话详情
 * POST {base}/conversations/{id}/messages      {"text": "..."} → SSE 事件流
 * POST {base}/conversations/{id}/cancel        取消
 * POST {base}/conversations/{id}/steer         {"text": "..."} 运行中插话
 * GET  {base}/approvals?conversationId=...     等待中的审批
 * POST {base}/approvals/{requestId}            {"decision": "allow|always|deny", "message": "..."}
 * </pre>
 * 配置 {@code agentcore.web.token} 后，除 health 外的请求都要带 {@code Authorization: Bearer <token>}（见 {@link BearerTokenInterceptor}）。
 */
@RestController
@RequestMapping("${agentcore.web.base-path:/api/agent}")
public class AgentController {

    private final AgentService service;
    private final AsyncApprover approver;
    private final long sseTimeoutMillis;

    public AgentController(AgentService service, AsyncApprover approver, Duration sseTimeout) {
        this.service = service;
        this.approver = approver;
        this.sseTimeoutMillis = sseTimeout == null ? 0 : sseTimeout.toMillis();
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return json(HttpStatus.OK, Json.object().put("status", "ok").put("model", service.engine().model().describe()));
    }

    @GetMapping("/conversations")
    public ResponseEntity<String> list(@RequestParam(defaultValue = "50") int limit) {
        ArrayNode conversations = Json.array();
        for (ConversationSummary summary : service.list(limit)) {
            conversations.addObject().put("id", summary.id().value()).put("title", summary.title())
                    .put("messages", summary.messageCount()).put("updated_at", String.valueOf(summary.updatedAt()))
                    .set("usage", DomainCodec.usage(summary.usage()));
        }
        return json(HttpStatus.OK, Json.object().set("conversations", conversations));
    }

    @PostMapping("/conversations")
    public ResponseEntity<String> create(@RequestBody(required = false) String body) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "web");
        Json.toMap(parse(body).path("metadata")).forEach((key, value) -> metadata.put(key, String.valueOf(value)));
        Conversation conversation = service.startConversation(metadata);
        return json(HttpStatus.CREATED, Json.object().put("id", conversation.id().value()));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<String> get(@PathVariable String id) {
        Conversation conversation = service.find(id).orElseThrow(() -> new IllegalArgumentException("conversation not found: " + id));
        ObjectNode detail = Json.object().put("id", id).put("title", conversation.title()).put("running", service.isRunning(id));
        ArrayNode messages = detail.putArray("messages");
        conversation.messages().forEach(message -> messages.add(DomainCodec.message(message)));
        detail.set("usage", DomainCodec.usage(conversation.usage()));
        return json(HttpStatus.OK, detail);
    }

    /**
     * 发消息，响应是一个 SSE 事件流：每个 AgentEvent 一帧，需要审批时推 approval_required。
     * 运行在 AgentService 的虚拟线程上，请求线程立刻返回；客户端断开或超时会取消这次运行。
     */
    @PostMapping(path = "/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String id, @RequestBody String body) {
        List<ContentBlock> input = userInput(parse(body));
        if (service.find(id).isEmpty()) {
            throw new IllegalArgumentException("conversation not found: " + id);
        }
        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        // 运行期间，这个会话的审批请求都推给当前这条 SSE 连接
        if (approver != null) {
            approver.attach(id, (PermissionRequest request) -> send(emitter, id, "approval_required", approvalRequired(request)));
        }
        AgentService.RunHandle handle = service.chatAsync(id, input,
                (AgentEvent event) -> send(emitter, id, event.type(), EventCodec.encode(event)));
        Runnable cancelIfRunning = () -> service.cancel(id);
        emitter.onTimeout(cancelIfRunning);
        emitter.onError(error -> cancelIfRunning.run());
        handle.result().whenComplete((result, failure) -> {
            if (approver != null) {
                approver.detach(id);
            }
            if (failure != null) {
                send(emitter, id, "error", Json.object().put("error", String.valueOf(failure)));
            } else if (result.error() != null) {
                send(emitter, id, "error", Json.object().put("error", result.error()));
            }
            emitter.complete();
        });
        return emitter;
    }

    /** {"text": "...", "images": [{"url": "..."} 或 {"media_type": "...", "data": "<base64>"}]}，至少要有一样。 */
    private static List<ContentBlock> userInput(JsonNode request) {
        List<ContentBlock> input = new ArrayList<>();
        String text = request.path("text").asText("");
        if (!text.isBlank()) {
            input.add(new TextBlock(text));
        }
        request.path("images").forEach(image -> input.add(image.hasNonNull("url") ? ImageBlock.url(image.get("url").asText())
                : ImageBlock.base64(image.path("media_type").asText("image/png"), image.path("data").asText())));
        if (input.isEmpty()) {
            throw new IllegalArgumentException("text or images required");
        }
        return input;
    }

    @PostMapping("/conversations/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable String id) {
        return json(HttpStatus.OK, Json.object().put("cancelled", service.cancel(id)));
    }

    @PostMapping("/conversations/{id}/steer")
    public ResponseEntity<String> steer(@PathVariable String id, @RequestBody String body) {
        return json(HttpStatus.OK, Json.object().put("accepted", service.steer(id, parse(body).path("text").asText(""))));
    }

    @GetMapping("/approvals")
    public ResponseEntity<String> pendingApprovals(@RequestParam(required = false) String conversationId) {
        ArrayNode approvals = Json.array();
        if (approver != null) {
            approver.pending(conversationId)
                    .forEach(request -> approvals.add(approvalRequired(request).put("conversation_id", request.conversationId())));
        }
        return json(HttpStatus.OK, Json.object().set("approvals", approvals));
    }

    @PostMapping("/approvals/{requestId}")
    public ResponseEntity<String> approve(@PathVariable String requestId, @RequestBody(required = false) String body) {
        boolean resolved = approver != null && approver.resolve(requestId, approval(parse(body)));
        return json(resolved ? HttpStatus.OK : HttpStatus.NOT_FOUND, Json.object().put("resolved", resolved));
    }

    // ------------------------------------------------------------------ 错误处理

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        HttpStatus status = String.valueOf(e.getMessage()).startsWith("conversation not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return json(status, Json.object().put("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException e) {
        return json(HttpStatus.CONFLICT, Json.object().put("error", e.getMessage()));
    }

    // ------------------------------------------------------------------ 工具方法

    private void send(SseEmitter emitter, String conversationId, String event, ObjectNode data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(Json.write(data)));
        } catch (IOException | IllegalStateException e) {
            service.cancel(conversationId); // 客户端已断开：取消任务
        }
    }

    /** 审批请求 → SSE 事件 approval_required 的数据（也用于等待中审批的列表）。 */
    static ObjectNode approvalRequired(PermissionRequest request) {
        ObjectNode data = Json.object().put("request_id", request.requestId()).put("tool", request.tool())
                .put("summary", request.summary()).put("subject", request.subject()).put("reason", request.reason());
        data.set("arguments", Json.tree(request.arguments()));
        return data;
    }

    /** {"decision": "allow|always|deny", "message": "..."} → Approval；无法识别的一律当作拒绝。 */
    static Approval approval(JsonNode body) {
        return switch (body.path("decision").asText("deny")) {
            case "allow" -> Approval.allow();
            case "always" -> Approval.always();
            default -> new Approval(Decision.DENY, false, body.path("message").asText(""));
        };
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return Json.object();
        }
        try {
            return Json.read(body);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON body");
        }
    }

    private static ResponseEntity<String> json(HttpStatus status, JsonNode body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Json.write(body));
    }
}
