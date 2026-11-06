package com.agentcore.domain.llm.gateway;

import static com.agentcore.domain.testsupport.FakeModel.reply;
import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.MalformedOutputException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;
import com.agentcore.domain.testsupport.FakeModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetryingModelGatewayTest {

    static final ModelRequest REQ = ModelRequest.of("", List.of(Message.user("hi")), List.of());

    /** 记录重试通知。 */
    static final class Recorder implements ModelStreamListener {
        final List<String> calls = new ArrayList<>();

        @Override
        public void onRetry(int attempt, long delayMillis, String error) {
            calls.add("retry#" + attempt + ":" + error);
        }

        @Override
        public void onMalformedOutput(String error) {
            calls.add("malformed:" + error);
        }
    }

    RetryingModelGateway retrying(FakeModel m) {
        return new RetryingModelGateway(m, 2, Duration.ofMillis(1), 1);
    }

    @Test
    void retriesTransientAndMalformedErrors() {
        FakeModel m = new FakeModel().thenThrow(new ModelException("rate limited", true))
                .thenThrow(new MalformedOutputException("bad json", null)).then(reply("ok"));
        Recorder r = new Recorder();
        assertEquals("ok", retrying(m).stream(REQ, r, new CancellationToken()).message().text());
        assertEquals(List.of("retry#1:rate limited", "malformed:bad json"), r.calls);
        assertEquals(3, m.requests.size());
    }

    @Test
    void givesUpAfterLimitsAndPassesThroughOthers() {
        FakeModel tooMany = new FakeModel().thenThrow(new ModelException("503", true)).thenThrow(new ModelException("503", true))
                .thenThrow(new ModelException("503", true));
        assertThrows(ModelException.class, () -> retrying(tooMany).stream(REQ, ModelStreamListener.NOOP, new CancellationToken()));
        assertEquals(3, tooMany.requests.size(), "最多重试 2 次");

        FakeModel fatal = new FakeModel().thenThrow(new ModelException("bad key", false));
        assertThrows(ModelException.class, () -> retrying(fatal).stream(REQ, ModelStreamListener.NOOP, new CancellationToken()));
        assertEquals(1, fatal.requests.size(), "不可重试的错误不重试");

        FakeModel overflow = new FakeModel().thenThrow(new ContextOverflowException("too long", null));
        assertThrows(ContextOverflowException.class, () -> retrying(overflow).stream(REQ, ModelStreamListener.NOOP, new CancellationToken()));
        assertEquals(1, overflow.requests.size(), "超窗交给引擎压缩，不在这里重试");
    }

    @Test
    void cancelDuringBackoffStopsImmediately() {
        FakeModel m = new FakeModel().thenThrow(new ModelException("rate limited", true)).then(reply("never"));
        CancellationToken cancel = new CancellationToken();
        RetryingModelGateway slow = new RetryingModelGateway(m, 3, Duration.ofSeconds(30), 1);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // ignore
            }
            cancel.cancel();
        });
        long t0 = System.currentTimeMillis();
        assertThrows(CancelledException.class, () -> slow.stream(REQ, ModelStreamListener.NOOP, cancel));
        assertTrue(System.currentTimeMillis() - t0 < 5_000, "等待重试期间被取消，不会等满 30 秒");
    }

    @Test
    void retryDelayIsCappedAndFollowsRetryAfter() {
        RetryingModelGateway gateway = new RetryingModelGateway(new FakeModel(), 100, Duration.ofSeconds(1), 1);
        ModelException plain = new ModelException("overloaded", true);
        long jitter = 1000;
        assertTrue(gateway.retryDelayMillis(0, plain) < 1000 + jitter);
        assertTrue(gateway.retryDelayMillis(70, plain) <= RetryingModelGateway.MAX_BACKOFF_SECONDS * 1000 + jitter,
                "重试次数再多也不超过上限，也不会因为移位溢出变成负数");
        assertTrue(gateway.retryDelayMillis(70, plain) > 0);

        ModelException withRetryAfter = new ModelException("rate limited", true, 429, null, Duration.ofSeconds(7));
        assertEquals(7000, gateway.retryDelayMillis(0, withRetryAfter), "服务端给了 Retry-After 就按它等");
        ModelException absurd = new ModelException("rate limited", true, 429, null, Duration.ofHours(3));
        assertEquals(RetryingModelGateway.MAX_RETRY_AFTER_SECONDS * 1000, gateway.retryDelayMillis(0, absurd));
    }

    @Test
    void duplicateOrBlankToolCallIdsAreRetriedAsMalformedOutput() {
        ToolCall first = ToolCall.of("same-id", "read", Map.of("path", "a"));
        ToolCall second = ToolCall.of("same-id", "read", Map.of("path", "b"));
        FakeModel m = new FakeModel().then(reply("", first, second)).then(reply("ok"));
        Recorder recorder = new Recorder();
        assertEquals("ok", retrying(m).stream(REQ, recorder, new CancellationToken()).message().text());
        assertTrue(recorder.calls.get(0).startsWith("malformed:tool call id"), String.valueOf(recorder.calls));
    }
}
