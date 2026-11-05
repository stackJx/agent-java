package com.agentcore.domain.testsupport;

import com.agentcore.domain.context.service.TokenEstimator;
import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.Message;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.conversation.message.ToolCall;
import com.agentcore.domain.conversation.model.TokenUsage;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.llm.model.StopReason;
import com.agentcore.domain.shared.cancel.CancellationToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 测试用假模型（领域层测试不能依赖基础设施）。 */
public final class FakeModel implements ModelGateway {

    private static final AtomicInteger IDS = new AtomicInteger();
    final ConcurrentLinkedDeque<Function<ModelRequest, ModelResponse>> script = new ConcurrentLinkedDeque<>();
    public final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    private final int window;
    Function<ModelRequest, ModelResponse> otherwise;

    public FakeModel() {
        this(200_000);
    }

    public FakeModel(int window) {
        this.window = window;
    }

    public FakeModel then(ModelResponse r) {
        script.add(q -> r);
        return this;
    }

    public FakeModel then(Function<ModelRequest, ModelResponse> f) {
        script.add(f);
        return this;
    }

    public FakeModel thenThrow(RuntimeException e) {
        script.add(q -> {
            throw e;
        });
        return this;
    }

    public FakeModel otherwise(Function<ModelRequest, ModelResponse> f) {
        otherwise = f;
        return this;
    }

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String model() {
        return "fake";
    }

    @Override
    public int contextWindow() {
        return window;
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
        requests.add(request);
        Function<ModelRequest, ModelResponse> f = script.poll();
        if (f == null) {
            f = otherwise;
        }
        if (f == null) {
            throw new ModelException("script exhausted", false);
        }
        ModelResponse r = f.apply(request);
        r.message().content().forEach(b -> {
            if (b instanceof TextBlock t) {
                listener.onText(t.text());
            }
        });
        if (r.usage().isEmpty()) {
            long in = TokenEstimator.estimate(request.system()) + TokenEstimator.estimate(request.messages());
            r = new ModelResponse(r.message(), r.stopReason(), new TokenUsage(in, TokenEstimator.estimate(r.message()), 0, 0, 1),
                    r.model(), r.rawStopReason(), r.stopDetails());
        }
        return r;
    }

    public static ToolCall call(String name, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return ToolCall.of("call_" + IDS.incrementAndGet(), name, args);
    }

    public static ModelResponse reply(String text, ToolCall... calls) {
        return reply(text, null, calls);
    }

    public static ModelResponse reply(String text, StopReason stop, ToolCall... calls) {
        List<ContentBlock> content = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            content.add(new TextBlock(text));
        }
        content.addAll(List.of(calls));
        StopReason s = stop != null ? stop : calls.length > 0 ? StopReason.TOOL_USE : StopReason.END_TURN;
        return new ModelResponse(Message.assistant(content, null), s, TokenUsage.ZERO, "fake", s.wire(), null);
    }
}
