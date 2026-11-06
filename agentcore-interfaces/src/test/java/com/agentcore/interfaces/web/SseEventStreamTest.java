package com.agentcore.interfaces.web;

import static org.junit.jupiter.api.Assertions.*;

import com.agentcore.infrastructure.json.Json;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 写出线程把事件写到网络，发事件的一方不用等。 */
class SseEventStreamTest {

    /** 模拟网速很慢的客户端：每写一帧要 100 毫秒。 */
    static final class SlowEmitter extends SseEmitter {
        final List<String> written = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            written.add(builder.build().stream().map(part -> String.valueOf(part.getData())).collect(Collectors.joining()));
        }

        @Override
        public void complete() {
            completed.countDown();
        }
    }

    @Test
    void sendingDoesNotWaitForASlowClientAndKeepsTheOrder() throws Exception {
        SlowEmitter emitter = new SlowEmitter();
        SseEventStream stream = new SseEventStream(emitter, () -> fail("client is still there"));
        long startedAt = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            stream.send("text_delta", Json.object().put("n", i));
        }
        stream.complete();
        long enqueueMillis = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(enqueueMillis < 500, "发 10 个事件只是放进队列（实际用了 " + enqueueMillis + "ms），不等每帧 100ms 的网络写入");

        assertTrue(emitter.completed.await(5, TimeUnit.SECONDS), "写完队列里的事件后结束响应");
        assertEquals(10, emitter.written.size());
        for (int i = 0; i < 10; i++) {
            assertTrue(emitter.written.get(i).contains("\"n\":" + i), "顺序不变");
        }
    }

    @Test
    void aFailedWriteMeansTheClientIsGone() throws Exception {
        CountDownLatch gone = new CountDownLatch(1);
        SseEmitter broken = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("Broken pipe");
            }
        };
        SseEventStream stream = new SseEventStream(broken, gone::countDown);
        stream.send("text_delta", Json.object().put("text", "hi"));
        assertTrue(gone.await(5, TimeUnit.SECONDS), "写失败就取消这次运行");
        stream.send("text_delta", Json.object().put("text", "dropped")); // 之后的事件直接丢弃，不报错
    }
}
