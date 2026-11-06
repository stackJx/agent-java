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
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * GET    {base}/health                           健康检查（配置了 token 也不需要鉴权）
 * GET    {base}/conversations?limit=50           会话列表（最多 200 个）
 * POST   {base}/conversations                    新建会话
 * GET    {base}/conversations/{id}               会话详情（图片默认不带数据，?include_images=true 才带）
 * DELETE {base}/conversations/{id}               删除会话（运行中返回 409）
 * POST   {base}/conversations/{id}/messages      {"text": "..."} → SSE 事件流
 * POST   {base}/conversations/{id}/cancel        取消
 * POST   {base}/conversations/{id}/steer         {"text": "..."} 运行中插话
 * GET    {base}/approvals?conversationId=...     等待中的审批
 * POST   {base}/approvals/{requestId}            {"decision": "allow|always|deny", "message": "..."}
 * </pre>
 * 配置 {@code agentcore.web.token} / {@code agentcore.web.user-tokens} 后，除 health 外的请求都要带
 * {@code Authorization: Bearer <token>}（见 {@link BearerTokenInterceptor}）；都没有配置时默认只接受本机请求（见 {@link LocalOnlyInterceptor}）。
 *
 * <p><b>多用户</b>：能认出用户时（用户 token 或 Spring Security，见 {@link WebCaller}），新建的会话归这个用户，
 * 他只能看到、操作自己的会话和审批；别人的会话一律当作不存在（404）。认不出用户时可以访问所有会话。
 *
 * <p>收到 {@code retrying} 事件表示这一轮要从头重新生成：前端应丢弃这条回复已经显示的部分。
 */
@RestController
@RequestMapping("${agentcore.web.base-path:/api/agent}")
public class AgentController {

    /** 会话列表一次最多返回多少个。 */
    static final int MAX_LIST_LIMIT = 200;
    private static final String CONVERSATION_NOT_FOUND = "conversation not found: ";

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

    // ------------------------------------------------------------------ 会话

    @GetMapping("/conversations")
    public ResponseEntity<String> list(@RequestParam(defaultValue = "50") int limit, HttpServletRequest request) {
        int boundedLimit = Math.max(1, Math.min(MAX_LIST_LIMIT, limit));
        ArrayNode conversations = Json.array();
        for (ConversationSummary summary : service.list(boundedLimit, WebCaller.userOf(request))) {
            conversations.addObject().put("id", summary.id().value()).put("title", summary.title())
                    .put("messages", summary.messageCount()).put("updated_at", String.valueOf(summary.updatedAt()))
                    .set("usage", DomainCodec.usage(summary.usage()));
        }
        return json(HttpStatus.OK, Json.object().set("conversations", conversations));
    }

    @PostMapping("/conversations")
    public ResponseEntity<String> create(@RequestBody(required = false) String body, HttpServletRequest request) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "web");
        Json.toMap(parse(body).path("metadata")).forEach((key, value) -> metadata.put(key, String.valueOf(value)));
        Conversation conversation = service.startConversation(metadata, WebCaller.userOf(request));
        return json(HttpStatus.CREATED, Json.object().put("id", conversation.id().value()));
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<String> get(@PathVariable String id, @RequestParam(name = "include_images", defaultValue = "false")
                                      boolean includeImages, HttpServletRequest request) {
        Conversation conversation = accessibleConversation(id, request);
        ObjectNode detail = Json.object().put("id", id).put("title", conversation.title()).put("running", service.isRunning(id));
        ArrayNode messages = detail.putArray("messages");
        conversation.messages().forEach(message -> {
            ObjectNode encoded = DomainCodec.message(message);
            messages.add(includeImages ? encoded : ImageData.omitted(encoded));
        });
        detail.set("usage", DomainCodec.usage(conversation.usage()));
        return json(HttpStatus.OK, detail);
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, HttpServletRequest request) {
        accessibleConversation(id, request);
        return json(HttpStatus.OK, Json.object().put("deleted", service.delete(id)));
    }

    /**
     * 发消息，响应是一个 SSE 事件流：每个 AgentEvent 一帧，需要审批时推 approval_required。
     * 运行在 AgentService 的虚拟线程上，请求线程立刻返回；事件由这条连接自己的写出线程发送（见 {@link SseEventStream}）。
     * 客户端断开、太久不读或超时都会取消这次运行。
     */
    @PostMapping(path = "/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String id, @RequestBody String body, HttpServletRequest request) {
        List<ContentBlock> input = userInput(parse(body));
        accessibleConversation(id, request);
        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        Runnable cancelRun = () -> service.cancel(id);
        SseEventStream stream = new SseEventStream(emitter, cancelRun);
        // 运行期间，这个会话的审批请求都推给当前这条 SSE 连接。
        // 占住会话之后才挂上：会话正在运行时 chatAsync 直接返回 409，不会抢走正在运行的那次的审批通道
        AsyncApprover.Channel channel = (PermissionRequest approval) -> stream.send("approval_required", approvalRequired(approval));
        AgentService.RunHandle handle = service.chatAsync(id, input,
                (AgentEvent event) -> stream.send(event.type(), EventCodec.encode(event)),
                () -> {
                    if (approver != null) {
                        approver.attach(id, channel);
                    }
                });
        emitter.onTimeout(cancelRun);
        emitter.onError(error -> cancelRun.run());
        handle.result().whenComplete((result, failure) -> {
            if (approver != null) {
                approver.detach(id, channel);
            }
            if (failure != null) {
                stream.send("error", Json.object().put("error", String.valueOf(failure)));
            } else if (result.error() != null) {
                stream.send("error", Json.object().put("error", result.error()));
            }
            stream.complete();
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
    public ResponseEntity<String> cancel(@PathVariable String id, HttpServletRequest request) {
        accessibleConversation(id, request);
        return json(HttpStatus.OK, Json.object().put("cancelled", service.cancel(id)));
    }

    /** accepted=false 表示没有正在进行的运行（或者它正在收尾）：应该把这段话作为新消息发送。 */
    @PostMapping("/conversations/{id}/steer")
    public ResponseEntity<String> steer(@PathVariable String id, @RequestBody String body, HttpServletRequest request) {
        accessibleConversation(id, request);
        return json(HttpStatus.OK, Json.object().put("accepted", service.steer(id, parse(body).path("text").asText(""))));
    }

    // ------------------------------------------------------------------ 审批

    @GetMapping("/approvals")
    public ResponseEntity<String> pendingApprovals(@RequestParam(required = false) String conversationId, HttpServletRequest request) {
        ArrayNode approvals = Json.array();
        if (approver != null) {
            approver.pending(conversationId).stream()
                    .filter(pending -> isAccessible(pending.conversationId(), request))
                    .forEach(pending -> approvals.add(approvalRequired(pending).put("conversation_id", pending.conversationId())));
        }
        return json(HttpStatus.OK, Json.object().set("approvals", approvals));
    }

    /** 只能回答自己会话里的审批；别人的审批和不存在的一样返回 404。 */
    @PostMapping("/approvals/{requestId}")
    public ResponseEntity<String> approve(@PathVariable String requestId, @RequestBody(required = false) String body,
                                          HttpServletRequest request) {
        boolean resolved = approver != null && approver.pendingRequest(requestId)
                .filter(pending -> isAccessible(pending.conversationId(), request))
                .map(pending -> approver.resolve(requestId, approval(parse(body))))
                .orElse(false);
        return json(resolved ? HttpStatus.OK : HttpStatus.NOT_FOUND, Json.object().put("resolved", resolved));
    }

    // ------------------------------------------------------------------ 访问控制

    /**
     * 取出会话，并确认调用者可以访问它：认不出用户时都可以；认出了用户，只能访问 owner 是他的会话。
     * 不存在和不属于他都报 "conversation not found"（404），不暴露别人的会话是否存在。
     */
    private Conversation accessibleConversation(String id, HttpServletRequest request) {
        String user = WebCaller.userOf(request);
        return service.find(id)
                .filter(conversation -> user == null || user.equals(conversation.owner()))
                .orElseThrow(() -> new IllegalArgumentException(CONVERSATION_NOT_FOUND + id));
    }

    private boolean isAccessible(String conversationId, HttpServletRequest request) {
        try {
            accessibleConversation(conversationId, request);
            return true;
        } catch (IllegalArgumentException notAccessible) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 错误处理

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        HttpStatus status = String.valueOf(e.getMessage()).startsWith(CONVERSATION_NOT_FOUND) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return json(status, Json.object().put("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> conflict(IllegalStateException e) {
        return json(HttpStatus.CONFLICT, Json.object().put("error", e.getMessage()));
    }

    // ------------------------------------------------------------------ 工具方法

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
