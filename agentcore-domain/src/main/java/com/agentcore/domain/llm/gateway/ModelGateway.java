package com.agentcore.domain.llm.gateway;

import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.shared.cancel.CancellationToken;

/**
 * 大模型网关（端口）。基础设施层为每家厂商提供实现：
 * 把厂商无关的 {@link ModelRequest} 转为厂商请求，流式回调增量，最后返回完整的 {@link ModelResponse}。
 */
public interface ModelGateway extends AutoCloseable {

    String provider();

    String model();

    /** 上下文窗口大小（token）。 */
    int contextWindow();

    ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel);

    default ModelResponse complete(ModelRequest request) {
        return stream(request, ModelStreamListener.NOOP, CancellationToken.NONE);
    }

    default String describe() {
        return provider() + ":" + model();
    }

    @Override
    default void close() {
    }
}
