package com.agentcore.interfaces.web;

import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 一条 SSE 连接的事件写出：引擎线程只把事件放进有上限的队列，由这条连接自己的虚拟线程写到网络。
 * 客户端网速慢时，引擎（以及并行执行的工具）不会被网络写入拖住；事件顺序保持不变。
 *
 * <p>写失败（客户端断开），或者队列满了 {@value #ENQUEUE_TIMEOUT_SECONDS} 秒还放不进去（客户端长时间不读），
 * 都当作客户端已经离开：调用 onClientGone（通常是取消这次运行），之后的事件直接丢弃。
 */
final class SseEventStream {

    /** 队列最多积压多少个事件。文字增量一个字一个事件，留足余量。 */
    private static final int MAX_QUEUED_EVENTS = 10_000;
    private static final long ENQUEUE_TIMEOUT_SECONDS = 30;

    /** 一帧 SSE：事件名 + JSON 数据。END 表示写完剩下的就结束响应。 */
    private record Frame(String event, String data) {
    }

    private static final Frame END = new Frame(null, null);

    private final SseEmitter emitter;
    private final Runnable onClientGone;
    private final BlockingQueue<Frame> queue = new LinkedBlockingQueue<>(MAX_QUEUED_EVENTS);
    private volatile boolean clientGone;

    SseEventStream(SseEmitter emitter, Runnable onClientGone) {
        this.emitter = emitter;
        this.onClientGone = onClientGone;
        Thread.ofVirtual().name("agent-sse-writer").start(this::writeUntilEnd);
    }

    /** 把一个事件排进队列（不等网络写完）。 */
    void send(String event, ObjectNode data) {
        enqueue(new Frame(event, Json.write(data)));
    }

    /** 写完队列里剩下的事件后结束响应。 */
    void complete() {
        enqueue(END);
        if (clientGone) {
            completeQuietly();
        }
    }

    private void enqueue(Frame frame) {
        if (clientGone) {
            return;
        }
        try {
            if (!queue.offer(frame, ENQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                clientIsGone(); // 客户端太久不读，队列一直是满的
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            clientIsGone();
        }
    }

    /** 写出线程：按顺序写，直到遇到 END 或者写失败。 */
    private void writeUntilEnd() {
        try {
            Frame frame;
            while ((frame = queue.take()) != END) {
                emitter.send(SseEmitter.event().name(frame.event()).data(frame.data()));
            }
            completeQuietly();
        } catch (IOException | IllegalStateException clientDisconnected) {
            clientIsGone();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            clientIsGone();
        }
    }

    private void clientIsGone() {
        if (clientGone) {
            return;
        }
        clientGone = true;
        queue.clear();
        onClientGone.run();
    }

    private void completeQuietly() {
        try {
            emitter.complete();
        } catch (IllegalStateException alreadyCompleted) {
            // 连接已经结束
        }
    }
}
