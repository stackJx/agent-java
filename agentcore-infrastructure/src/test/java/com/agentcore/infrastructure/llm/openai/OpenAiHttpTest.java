package com.agentcore.infrastructure.llm.openai;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.infrastructure.FakeSseServer;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 流式读取的失败情况：对方不发数据也不断开、流没结束就断开；以及错误分类和 Retry-After。 */
class OpenAiHttpTest {

    static final ModelRequest REQUEST = ModelRequest.of("", List.of(Message.user("hi")), List.of());

    static OpenAiCompatibleModelGateway gateway(String baseUrl, Duration timeout) {
        return new OpenAiCompatibleModelGateway("fake-model", new OpenAiCompatibleModelGateway.Options("fake", baseUrl, "k", 100,
                null, 8000, null, false, false, timeout, Map.of(), Map.of()));
    }

    @Test
    void stalledStreamIsAbortedAndRetryable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(FakeSseServer.oaChunk("{\"content\":\"hel\"}", null).getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(5000); // 不再发数据，也不断开
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleModelGateway gateway = gateway("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300));
            long startedAt = System.nanoTime();
            ModelException failure = assertThrows(ModelException.class,
                    () -> gateway.stream(REQUEST, ModelStreamListener.NOOP, new CancellationToken()));
            assertTrue(failure.retryable());
            assertTrue(failure.getMessage().contains("stream stalled"), failure.getMessage());
            assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toMillis() < 3000, "没有等到对方断开");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamThatEndsWithoutFinishIsRetryableInsteadOfAFinalAnswer() throws Exception {
        try (FakeSseServer server = new FakeSseServer()) {
            server.respond(List.of(FakeSseServer.oaChunk("{\"content\":\"半截回\"}", null))); // 没有 finish_reason，也没有 [DONE]
            server.respond(List.of(FakeSseServer.oaChunk("{\"content\":\"完整\"}", "stop"))); // 有 finish_reason，没有 [DONE] 也算完整
            OpenAiCompatibleModelGateway gateway = gateway(server.url(), Duration.ofSeconds(30));
            ModelException failure = assertThrows(ModelException.class,
                    () -> gateway.stream(REQUEST, ModelStreamListener.NOOP, new CancellationToken()));
            assertTrue(failure.retryable());
            assertEquals("完整", gateway.stream(REQUEST, ModelStreamListener.NOOP, new CancellationToken()).message().text());
        }
    }

    @Test
    void retryAfterAndContextOverflowAreRecognized() {
        assertEquals(Duration.ofSeconds(12), OpenAiHttp.parseRetryAfter("12"));
        assertEquals(Duration.ofMillis(1500), OpenAiHttp.parseRetryAfter("1.5"));
        assertEquals(Duration.ZERO, OpenAiHttp.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"), "过去的日期 → 立即重试");
        assertNull(OpenAiHttp.parseRetryAfter("soon"));

        ModelException limited = OpenAiHttp.httpError(429, "{\"error\":\"rate limited\"}", Duration.ofSeconds(3));
        assertTrue(limited.retryable());
        assertEquals(Duration.ofSeconds(3), limited.retryAfter());

        assertInstanceOf(ContextOverflowException.class,
                OpenAiHttp.httpError(400, "This model's maximum context length is 65536 tokens", null));
        assertInstanceOf(ContextOverflowException.class, OpenAiHttp.httpError(400, "Range of input length should be [1, 98304]", null));
        ModelException notOverflow = OpenAiHttp.httpError(400, "tools[0].function.description is too long", null);
        assertFalse(notOverflow instanceof ContextOverflowException, "参数太长不等于上下文超窗");
        assertFalse(notOverflow.retryable());
    }

    @Test
    void blankAndDuplicateToolCallIdsAreReplaced() throws Exception {
        try (FakeSseServer server = new FakeSseServer()) {
            server.respond(List.of(
                    FakeSseServer.oaChunk("{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}", null),
                    FakeSseServer.oaChunk("{\"tool_calls\":[{\"index\":1,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}", null),
                    FakeSseServer.oaChunk("{\"tool_calls\":[{\"index\":2,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}", null),
                    FakeSseServer.oaChunk("{}", "tool_calls"),
                    FakeSseServer.OA_DONE));
            List<String> ids = gateway(server.url(), Duration.ofSeconds(30))
                    .stream(REQUEST, ModelStreamListener.NOOP, new CancellationToken())
                    .message().toolCalls().stream().map(call -> call.id()).toList();
            assertEquals(3, ids.size());
            assertEquals(3, ids.stream().distinct().count(), "空 ID 和重复 ID 都换成了新的：" + ids);
            assertTrue(ids.stream().noneMatch(String::isBlank));
            assertEquals("call_1", ids.get(1), "第一次出现的 ID 保持不变");
        }
    }
}
