package com.example.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 在 Spring Boot 4 管理的依赖版本下，走真实的 anthropic-java SDK（指向本地假 Claude 服务），
 * 验证：自动装配、@AgentTool 业务工具、钩子、Web 审批、SSE 全链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleAgentApplicationTest {

    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static final ConcurrentLinkedDeque<String> RESPONSES = new ConcurrentLinkedDeque<>();
    static final HttpServer FAKE_CLAUDE;
    static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("agentcore-sample");
            FAKE_CLAUDE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FAKE_CLAUDE.createContext("/", ex -> {
                REQUESTS.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = RESPONSES.poll().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("content-type", "text/event-stream");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            });
            FAKE_CLAUDE.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("agentcore.model", () -> "anthropic:claude-opus-5-5");
        r.add("agentcore.providers.anthropic.base-url", () -> "http://127.0.0.1:" + FAKE_CLAUDE.getAddress().getPort());
        r.add("agentcore.providers.anthropic.api-key", () -> "test-key");
        r.add("agentcore.providers.anthropic.max-retries", () -> "0");
        r.add("agentcore.sessions.dir", () -> DATA.resolve("sessions").toString());
    }

    @AfterAll
    static void stop() {
        FAKE_CLAUDE.stop(0);
    }

    static String event(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    /** 拼一条 Anthropic 流式消息：可选文本 + 可选一个 tool_use。 */
    static String claudeMessage(String text, String toolId, String toolName, String inputJson, String stop) {
        StringBuilder sb = new StringBuilder();
        sb.append(event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":1,\"cache_read_input_tokens\":1000,\"cache_creation_input_tokens\":0}}}"));
        int i = 0;
        if (text != null) {
            sb.append(event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
            sb.append(event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}"));
            sb.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
            i++;
        }
        if (toolId != null) {
            sb.append(event("content_block_start", "{\"type\":\"content_block_start\",\"index\":" + i + ",\"content_block\":{\"type\":\"tool_use\",\"id\":\""
                    + toolId + "\",\"name\":\"" + toolName + "\",\"input\":{}}}"));
            sb.append(event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + i
                    + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + quote(inputJson) + "}}"));
            sb.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":" + i + "}"));
        }
        sb.append(event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":30}}"));
        sb.append(event("message_stop", "{\"type\":\"message_stop\"}"));
        return sb.toString();
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    String post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void customerServiceAgentEndToEndThroughAnthropicSdk() throws Exception {
        RESPONSES.add(claudeMessage("我先查一下订单。", "toolu_1", "get_order", "{\"orderId\": \"A1001\"}", "tool_use"));
        RESPONSES.add(claudeMessage(null, "toolu_2", "refund", "{\"orderId\": \"A1002\", \"reason\": \"LATE\", \"amount\": 58.5}", "tool_use"));
        RESPONSES.add(claudeMessage("A1001 已发货（顺丰 SF1234567890），A1002 已发起退款 58.5 元。", null, null, null, "end_turn"));

        String created = post("/api/agent/conversations", "");
        Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(created);
        assertThat(m.find()).isTrue();
        String id = m.group(1);

        HttpResponse<java.util.stream.Stream<String>> resp = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/agent/conversations/" + id + "/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"A1001 到哪了？A1002 太慢了帮我退款\"}")).build(),
                HttpResponse.BodyHandlers.ofLines());
        StringBuilder text = new StringBuilder();
        String event = null;
        boolean approved = false;
        Iterator<String> it = resp.body().iterator();
        while (it.hasNext()) {
            String line = it.next();
            if (line.startsWith("event:")) {
                event = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                String data = line.substring(5).strip();
                if ("approval_required".equals(event)) {   // refund 非只读 → 前端确认
                    assertThat(data).contains("\"tool\":\"refund\"");
                    Matcher rm = Pattern.compile("\"request_id\":\"([^\"]+)\"").matcher(data);
                    assertThat(rm.find()).isTrue();
                    assertThat(post("/api/agent/approvals/" + rm.group(1), "{\"decision\":\"allow\"}")).contains("\"resolved\":true");
                    approved = true;
                } else if ("text_delta".equals(event)) {
                    Matcher tm = Pattern.compile("\"text\":\"(.*)\"").matcher(data);
                    if (tm.find()) {
                        text.append(tm.group(1));
                    }
                } else if ("run_finished".equals(event)) {
                    assertThat(data).contains("\"stop_reason\":\"end_turn\"");
                }
            }
        }
        assertThat(approved).isTrue();
        assertThat(text.toString()).isEqualTo("我先查一下订单。A1001 已发货（顺丰 SF1234567890），A1002 已发起退款 58.5 元。");

        // 发给 Claude 的请求：业务工具已注册、系统提示词带上了业务要求、工具结果正确回填
        String first = REQUESTS.get(0);
        assertThat(first).contains("\"name\":\"get_order\"", "\"name\":\"refund\"", "\"name\":\"todo_write\"", "电商平台的客服助手");
        assertThat(first).doesNotContain("\"name\":\"bash\"");
        assertThat(REQUESTS.get(1)).contains("\"tool_use_id\":\"toolu_1\"", "SF1234567890");
        assertThat(REQUESTS.get(2)).contains("预计 1-3 个工作日到账");
        assertThat(Files.list(DATA.resolve("sessions")).count()).isEqualTo(1);    // 会话已持久化
    }
}
