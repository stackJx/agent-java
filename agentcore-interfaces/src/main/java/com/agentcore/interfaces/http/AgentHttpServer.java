package com.agentcore.interfaces.http;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.agent.AgentEvent;
import com.agentcore.domain.agent.RunResult;
import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.Conversation;
import com.agentcore.domain.conversation.ConversationSummary;
import com.agentcore.domain.conversation.ImageBlock;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.infrastructure.json.DomainCodec;
import com.agentcore.infrastructure.json.EventCodec;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP + SSE 接口（JDK 内置 HttpServer + 虚拟线程，零依赖）。
 *
 * <pre>
 * GET  /health
 * GET  /api/conversations                      会话列表
 * POST /api/conversations                      新建会话 → {"id": ...}
 * GET  /api/conversations/{id}                 会话详情（消息、用量）
 * POST /api/conversations/{id}/messages        {"text": "...", "images": [{"media_type","data"}]} → SSE 事件流
 * POST /api/conversations/{id}/cancel          取消正在运行的任务
 * POST /api/conversations/{id}/steer           {"text": "..."} 运行中插话
 * POST /api/approvals/{requestId}              {"decision": "allow|always|deny", "message": "..."}
 * </pre>
 * 如果配置了 token，所有 /api 请求需带 {@code Authorization: Bearer <token>}。
 */
public final class AgentHttpServer implements AutoCloseable {

    private static final Pattern CONV = Pattern.compile("^/api/conversations/([A-Za-z0-9_.-]+)(?:/(messages|cancel|steer))?$");
    private static final Pattern APPROVAL = Pattern.compile("^/api/approvals/([A-Za-z0-9-]+)$");

    private final AgentService service;
    private final AsyncApprover approver;
    private final String token;
    private final HttpServer server;

    public AgentHttpServer(AgentService service, AsyncApprover approver, String host, int port, String token) throws IOException {
        this.service = service;
        this.approver = approver;
        this.token = token == null || token.isBlank() ? null : token;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", this::handle);
    }

    public AgentHttpServer start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/health")) {
                json(ex, 200, Json.object().put("status", "ok"));
                return;
            }
            if (token != null && !("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                json(ex, 401, Json.object().put("error", "unauthorized"));
                return;
            }
            if (path.equals("/api/conversations")) {
                if (method.equals("GET")) {
                    ArrayNode arr = Json.array();
                    for (ConversationSummary s : service.list(50)) {
                        arr.addObject().put("id", s.id().value()).put("title", s.title()).put("messages", s.messageCount())
                                .put("updated_at", String.valueOf(s.updatedAt())).set("usage", DomainCodec.usage(s.usage()));
                    }
                    json(ex, 200, Json.object().set("conversations", arr));
                } else if (method.equals("POST")) {
                    Conversation c = service.startConversation(Map.of("source", "http"));
                    json(ex, 201, Json.object().put("id", c.id().value()));
                } else {
                    json(ex, 405, Json.object().put("error", "method not allowed"));
                }
                return;
            }
            Matcher am = APPROVAL.matcher(path);
            if (am.matches() && method.equals("POST")) {
                boolean ok = approver.resolve(am.group(1), WebJson.approval(readJson(ex)));
                json(ex, ok ? 200 : 404, Json.object().put("resolved", ok));
                return;
            }
            Matcher cm = CONV.matcher(path);
            if (cm.matches()) {
                String id = cm.group(1);
                String action = cm.group(2);
                if (service.find(id).isEmpty()) {
                    json(ex, 404, Json.object().put("error", "conversation not found"));
                    return;
                }
                if (action == null && method.equals("GET")) {
                    Conversation c = service.find(id).get();
                    ObjectNode n = Json.object().put("id", id).put("title", c.title()).put("running", service.isRunning(id));
                    ArrayNode msgs = n.putArray("messages");
                    c.messages().forEach(m -> msgs.add(DomainCodec.message(m)));
                    n.set("usage", DomainCodec.usage(c.usage()));
                    json(ex, 200, n);
                } else if ("messages".equals(action) && method.equals("POST")) {
                    stream(ex, id, readJson(ex));
                } else if ("cancel".equals(action) && method.equals("POST")) {
                    json(ex, 200, Json.object().put("cancelled", service.cancel(id)));
                } else if ("steer".equals(action) && method.equals("POST")) {
                    json(ex, 200, Json.object().put("accepted", service.steer(id, readJson(ex).path("text").asText(""))));
                } else {
                    json(ex, 405, Json.object().put("error", "method not allowed"));
                }
                return;
            }
            json(ex, 404, Json.object().put("error", "not found"));
        } catch (IllegalStateException e) {
            json(ex, 409, Json.object().put("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            json(ex, 400, Json.object().put("error", e.getMessage()));
        } catch (RuntimeException e) {
            json(ex, 500, Json.object().put("error", e.toString()));
        } finally {
            ex.close();
        }
    }

    private void stream(HttpExchange ex, String id, JsonNode body) throws IOException {
        List<ContentBlock> input = new ArrayList<>();
        String text = body.path("text").asText("");
        if (!text.isBlank()) {
            input.add(new TextBlock(text));
        }
        body.path("images").forEach(img -> input.add(img.hasNonNull("url") ? ImageBlock.url(img.get("url").asText())
                : ImageBlock.base64(img.path("media_type").asText("image/png"), img.path("data").asText())));
        if (input.isEmpty()) {
            json(ex, 400, Json.object().put("error", "text or images required"));
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        Sink sink = (event, data) -> {
            synchronized (out) {
                try {
                    out.write(("event: " + event + "\ndata: " + Json.write(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    service.cancel(id); // 客户端断开：取消任务
                }
            }
        };
        approver.attach(id, req -> sink.send("approval_required", WebJson.approvalRequired(req)));
        try {
            AgentService.RunHandle handle = service.chatAsync(id, input, (AgentEvent e) -> sink.send(e.type(), EventCodec.encode(e)));
            RunResult r = handle.result().join();
            if (r.error() != null) {
                sink.send("error", Json.object().put("error", r.error()));
            }
        } catch (IllegalStateException e) {
            sink.send("error", Json.object().put("error", e.getMessage()));
        } finally {
            approver.detach(id);
        }
    }

    private interface Sink {
        void send(String event, ObjectNode data);
    }

    private static JsonNode readJson(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        if (b.length == 0) {
            return Json.object();
        }
        try {
            return Json.read(new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON body");
        }
    }

    private static void json(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
