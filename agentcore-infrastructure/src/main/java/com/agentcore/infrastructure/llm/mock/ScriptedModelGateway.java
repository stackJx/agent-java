package com.agentcore.infrastructure.llm.mock;

import com.agentcore.domain.context.TokenEstimator;
import com.agentcore.domain.conversation.ContentBlock;
import com.agentcore.domain.conversation.Message;
import com.agentcore.domain.conversation.TextBlock;
import com.agentcore.domain.conversation.ThinkingBlock;
import com.agentcore.domain.conversation.TokenUsage;
import com.agentcore.domain.conversation.ToolCall;
import com.agentcore.domain.model.ModelException;
import com.agentcore.domain.model.ModelGateway;
import com.agentcore.domain.model.ModelRequest;
import com.agentcore.domain.model.ModelResponse;
import com.agentcore.domain.model.ModelStreamListener;
import com.agentcore.domain.model.StopReason;
import com.agentcore.domain.shared.CancellationToken;
import com.agentcore.infrastructure.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 脚本化的假模型：单元测试、离线演示、回放。每个脚本项是一个 {@code Function<ModelRequest, ModelResponse>}，
 * 可以根据请求动态生成回复（顺便做断言），也可以抛出异常模拟失败。
 *
 * <pre>
 * var model = new ScriptedModelGateway()
 *     .then(reply("先看看", call("read_file", "path", "a.txt")))
 *     .then(reply("内容是 hello"));
 * </pre>
 */
public final class ScriptedModelGateway implements ModelGateway {

    private static final AtomicInteger IDS = new AtomicInteger();

    private final ConcurrentLinkedDeque<Function<ModelRequest, ModelResponse>> script = new ConcurrentLinkedDeque<>();
    private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    private final int contextWindow;
    private Function<ModelRequest, ModelResponse> fallback;

    public ScriptedModelGateway() {
        this(200_000);
    }

    public ScriptedModelGateway(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public ScriptedModelGateway then(ModelResponse r) {
        script.add(req -> r);
        return this;
    }

    public ScriptedModelGateway then(Function<ModelRequest, ModelResponse> f) {
        script.add(f);
        return this;
    }

    public ScriptedModelGateway thenThrow(RuntimeException e) {
        script.add(req -> {
            throw e;
        });
        return this;
    }

    /** 脚本用完后的默认回复。 */
    public ScriptedModelGateway otherwise(Function<ModelRequest, ModelResponse> f) {
        this.fallback = f;
        return this;
    }

    public List<ModelRequest> requests() {
        return requests;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public String model() {
        return "scripted";
    }

    @Override
    public int contextWindow() {
        return contextWindow;
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
        requests.add(request);
        Function<ModelRequest, ModelResponse> next = script.poll();
        if (next == null) {
            next = fallback;
        }
        if (next == null) {
            throw new ModelException("ScriptedModelGateway: script exhausted", false);
        }
        ModelResponse resp = next.apply(request);
        for (ContentBlock b : resp.message().content()) {
            cancel.throwIfCancelled();
            switch (b) {
                case ThinkingBlock t when t.text() != null -> listener.onThinking(t.text());
                case TextBlock t -> {
                    for (int i = 0; i < t.text().length(); i += 16) {
                        listener.onText(t.text().substring(i, Math.min(t.text().length(), i + 16)));
                    }
                }
                case ToolCall c -> listener.onToolCallStart(c.id(), c.name());
                default -> { }
            }
        }
        if (resp.usage().isEmpty()) { // 没给用量时按请求内容估算，让上下文压缩逻辑可测
            long in = TokenEstimator.estimate(request.system()) + TokenEstimator.estimate(String.valueOf(request.tools()))
                    + TokenEstimator.estimate(request.messages());
            resp = new ModelResponse(resp.message(), resp.stopReason(),
                    new TokenUsage(in, TokenEstimator.estimate(resp.message()), 0, 0, 1), resp.model(), resp.rawStopReason(),
                    resp.stopDetails());
        }
        return resp;
    }

    // ------------------------------------------------------------------ 便捷构造

    /** call("read_file", "path", "a.txt", "limit", 10) */
    public static ToolCall call(String name, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new ToolCall("call_" + IDS.incrementAndGet(), name, args, Json.write(args), null);
    }

    public static ModelResponse reply(String text, ToolCall... calls) {
        return reply(text, null, null, calls);
    }

    public static ModelResponse reply(String text, String thinking, StopReason stop, ToolCall... calls) {
        List<ContentBlock> content = new ArrayList<>();
        if (thinking != null && !thinking.isEmpty()) {
            content.add(ThinkingBlock.of(thinking, "mock"));
        }
        if (text != null && !text.isEmpty()) {
            content.add(new TextBlock(text));
        }
        content.addAll(List.of(calls));
        StopReason s = stop != null ? stop : calls.length > 0 ? StopReason.TOOL_USE : StopReason.END_TURN;
        return new ModelResponse(Message.assistant(content, null), s, TokenUsage.ZERO, "scripted", s.wire(), null);
    }
}
