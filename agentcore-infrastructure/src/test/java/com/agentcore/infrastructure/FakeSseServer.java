package com.agentcore.infrastructure;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/** 本地假模型服务：按顺序返回预先写好的 SSE 帧，并记录收到的请求。 */
public final class FakeSseServer implements AutoCloseable {

    public record Received(String path, Map<String, String> headers, JsonNode body) {
    }

    private final HttpServer server;
    private final ConcurrentLinkedDeque<List<String>> responses = new ConcurrentLinkedDeque<>();
    public final List<Received> requests = new CopyOnWriteArrayList<>();

    public FakeSseServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            requests.add(new Received(ex.getRequestURI().toString(), headers,
                    Json.read(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))));
            List<String> frames = responses.poll();
            ex.getResponseHeaders().add("content-type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (String f : frames == null ? List.<String>of() : frames) {
                    os.write(f.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        });
        server.start();
    }

    public FakeSseServer respond(List<String> frames) {
        responses.add(frames);
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- OpenAI chunks

    public static String oaChunk(String deltaJson, String finish) {
        String choice = "{\"index\":0,\"delta\":" + deltaJson + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}";
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fake-model\",\"choices\":[" + choice + "]}\n\n";
    }

    public static String oaUsage(String usageJson) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fake-model\",\"choices\":[],\"usage\":" + usageJson + "}\n\n";
    }

    public static final String OA_DONE = "data: [DONE]\n\n";

    // ---------------------------------------------------------------- OpenAI Responses events

    public static String rs(String type, ObjectNode data) {
        data.put("type", type);
        return "event: " + type + "\ndata: " + Json.write(data) + "\n\n";
    }

    /**
     * 按 Responses 协议的顺序生成一次完整的流：created → 每个 output 条目的 added / 增量 / done → completed。
     * items 是最终的 output 条目（message / function_call / reasoning）。
     */
    public static List<String> responsesStream(List<ObjectNode> items, String status, String incompleteReason, String usageJson) {
        List<String> f = new ArrayList<>();
        ObjectNode created = Json.object();
        created.putObject("response").put("id", "resp_1").put("status", "in_progress").putArray("output");
        f.add(rs("response.created", created));
        for (int i = 0; i < items.size(); i++) {
            ObjectNode item = items.get(i);
            int idx = i;
            String id = item.path("id").asText("item_" + i);
            ObjectNode skeleton = item.deepCopy();
            skeleton.remove(List.of("content", "summary", "encrypted_content"));
            if (skeleton.has("arguments")) {
                skeleton.put("arguments", "");
            }
            ObjectNode added = Json.object().put("output_index", i);
            added.set("item", skeleton);
            f.add(rs("response.output_item.added", added));
            switch (item.path("type").asText()) {
                case "message" -> item.path("content").forEach(c -> {
                    String text = c.has("text") ? c.path("text").asText() : c.path("refusal").asText();
                    String ev = c.has("text") ? "response.output_text.delta" : "response.refusal.delta";
                    for (String part : halves(text)) {
                        f.add(rs(ev, Json.object().put("item_id", id).put("output_index", idx).put("content_index", 0).put("delta", part)));
                    }
                });
                case "function_call" -> {
                    for (String part : halves(item.path("arguments").asText())) {
                        f.add(rs("response.function_call_arguments.delta", Json.object().put("item_id", id).put("output_index", i).put("delta", part)));
                    }
                }
                case "reasoning" -> {
                    for (int s = 0; s < item.path("summary").size(); s++) {
                        f.add(rs("response.reasoning_summary_part.added", Json.object().put("item_id", id).put("output_index", i).put("summary_index", s)));
                        f.add(rs("response.reasoning_summary_text.delta", Json.object().put("item_id", id).put("output_index", i)
                                .put("summary_index", s).put("delta", item.path("summary").get(s).path("text").asText())));
                    }
                }
                default -> { }
            }
            ObjectNode done = Json.object().put("output_index", i);
            done.set("item", item);
            f.add(rs("response.output_item.done", done));
        }
        ObjectNode resp = Json.object().put("id", "resp_1").put("model", "gpt-test-2026").put("status", status);
        if (incompleteReason != null) {
            resp.putObject("incomplete_details").put("reason", incompleteReason);
        } else {
            resp.putNull("incomplete_details");
        }
        items.forEach(resp.putArray("output")::add);
        resp.set("usage", Json.read(usageJson));
        ObjectNode completed = Json.object();
        completed.set("response", resp);
        f.add(rs("incomplete".equals(status) ? "response.incomplete" : "response.completed", completed));
        return f;
    }

    private static List<String> halves(String s) {
        int mid = s.length() / 2;
        return mid == 0 ? List.of(s) : List.of(s.substring(0, mid), s.substring(mid));
    }

    // ---------------------------------------------------------------- Anthropic events

    static String an(String type, ObjectNode data) {
        data.put("type", type);
        return "event: " + type + "\ndata: " + Json.write(data) + "\n\n";
    }

    /** blocks: 每个元素是 {"type":"text","text":..} / {"type":"thinking","thinking":..,"signature":..} / {"type":"tool_use","id","name","input"} */
    public static List<String> anthropicMessage(List<ObjectNode> blocks, String stopReason, long in, long out, long cacheRead) {
        List<String> f = new ArrayList<>();
        ObjectNode start = Json.object();
        ObjectNode msg = start.putObject("message");
        msg.put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", "claude-opus-5-5");
        msg.putArray("content");
        msg.putNull("stop_reason");
        msg.putNull("stop_sequence");
        msg.putObject("usage").put("input_tokens", in).put("output_tokens", 1).put("cache_read_input_tokens", cacheRead)
                .put("cache_creation_input_tokens", 0);
        f.add(an("message_start", start));
        for (int i = 0; i < blocks.size(); i++) {
            ObjectNode b = blocks.get(i);
            String t = b.get("type").asText();
            ObjectNode cbs = Json.object().put("index", i);
            switch (t) {
                case "text" -> {
                    cbs.putObject("content_block").put("type", "text").put("text", "");
                    f.add(an("content_block_start", cbs));
                    ObjectNode d = Json.object().put("index", i);
                    d.putObject("delta").put("type", "text_delta").put("text", b.get("text").asText());
                    f.add(an("content_block_delta", d));
                }
                case "thinking" -> {
                    cbs.putObject("content_block").put("type", "thinking").put("thinking", "").put("signature", "");
                    f.add(an("content_block_start", cbs));
                    ObjectNode d = Json.object().put("index", i);
                    d.putObject("delta").put("type", "thinking_delta").put("thinking", b.get("thinking").asText());
                    f.add(an("content_block_delta", d));
                    ObjectNode s = Json.object().put("index", i);
                    s.putObject("delta").put("type", "signature_delta").put("signature", b.get("signature").asText());
                    f.add(an("content_block_delta", s));
                }
                case "tool_use" -> {
                    ObjectNode cb = cbs.putObject("content_block").put("type", "tool_use").put("id", b.get("id").asText())
                            .put("name", b.get("name").asText());
                    cb.putObject("input");
                    f.add(an("content_block_start", cbs));
                    String raw = Json.write(b.get("input"));
                    ObjectNode d1 = Json.object().put("index", i);
                    d1.putObject("delta").put("type", "input_json_delta").put("partial_json", raw.substring(0, 5));
                    f.add(an("content_block_delta", d1));
                    ObjectNode d2 = Json.object().put("index", i);
                    d2.putObject("delta").put("type", "input_json_delta").put("partial_json", raw.substring(5));
                    f.add(an("content_block_delta", d2));
                }
                default -> throw new IllegalArgumentException(t);
            }
            f.add(an("content_block_stop", Json.object().put("index", i)));
        }
        ObjectNode md = Json.object();
        md.putObject("delta").put("stop_reason", stopReason).putNull("stop_sequence");
        md.putObject("usage").put("output_tokens", out);
        f.add(an("message_delta", md));
        f.add(an("message_stop", Json.object()));
        return f;
    }
}
