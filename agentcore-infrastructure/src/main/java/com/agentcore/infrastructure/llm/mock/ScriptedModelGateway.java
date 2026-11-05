package com.agentcore.infrastructure.llm.mock;

import com.agentcore.domain.context.service.TokenEstimator;
import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ThinkingBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.shared.cancel.CancellationToken;
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

    public ScriptedModelGateway then(ModelResponse response) {
        script.add(request -> response);
        return this;
    }

    public ScriptedModelGateway then(Function<ModelRequest, ModelResponse> responder) {
        script.add(responder);
        return this;
    }

    public ScriptedModelGateway thenThrow(RuntimeException e) {
        script.add(request -> {
            throw e;
        });
        return this;
    }

    /** 脚本用完后的默认回复。 */
    public ScriptedModelGateway otherwise(Function<ModelRequest, ModelResponse> responder) {
        this.fallback = responder;
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
        ModelResponse response = next.apply(request);
        // 模拟流式：思考一次给完，文字每 16 个字符回调一次
        for (ContentBlock block : response.message().content()) {
            cancel.throwIfCancelled();
            switch (block) {
                case ThinkingBlock thinking when thinking.text() != null -> listener.onThinking(thinking.text());
                case TextBlock text -> {
                    for (int i = 0; i < text.text().length(); i += 16) {
                        listener.onText(text.text().substring(i, Math.min(text.text().length(), i + 16)));
                    }
                }
                case ToolCall call -> listener.onToolCallStart(call.id(), call.name());
                default -> { }
            }
        }
        if (response.usage().isEmpty()) { // 没给用量时按请求内容估算，让上下文压缩逻辑可测
            long input = TokenEstimator.estimate(request.system()) + TokenEstimator.estimate(String.valueOf(request.tools()))
                    + TokenEstimator.estimate(request.messages());
            TokenUsage estimated = new TokenUsage(input, TokenEstimator.estimate(response.message()), 0, 0, 1);
            response = new ModelResponse(response.message(), response.stopReason(), estimated, response.model(),
                    response.rawStopReason(), response.stopDetails());
        }
        return response;
    }

    // ------------------------------------------------------------------ 便捷构造

    /** call("read_file", "path", "a.txt", "limit", 10) */
    public static ToolCall call(String name, Object... keyValues) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            args.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
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
        StopReason reason = stop != null ? stop : calls.length > 0 ? StopReason.TOOL_USE : StopReason.END_TURN;
        return new ModelResponse(Message.assistant(content, null), reason, TokenUsage.ZERO, "scripted", reason.wire(), null);
    }
}
