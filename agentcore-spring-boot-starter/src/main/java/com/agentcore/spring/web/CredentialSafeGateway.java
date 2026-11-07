package com.agentcore.spring.web;

import com.agentcore.domain.llm.exception.ContextOverflowException;
import com.agentcore.domain.llm.exception.MalformedOutputException;
import com.agentcore.domain.llm.exception.ModelException;
import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.domain.llm.gateway.ModelStreamListener;
import com.agentcore.domain.llm.model.ModelRequest;
import com.agentcore.domain.llm.model.ModelResponse;
import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.cancel.CancelledException;

/**
 * 包在页面配置的模型外面：出错时只保留错误的种类（超窗、输出无法解析、可否重试），丢掉原始信息和原因。
 * 厂商返回的错误内容可能把请求里的 Authorization 头原样带回来，而错误信息会显示在页面上、写进日志。
 */
final class CredentialSafeGateway implements ModelGateway {

    private final ModelGateway delegate;

    CredentialSafeGateway(ModelGateway delegate) {
        this.delegate = delegate;
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public int contextWindow() {
        return delegate.contextWindow();
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener, CancellationToken cancel) {
        try {
            return delegate.stream(request, listener, cancel);
        } catch (CancelledException e) {
            throw e;
        } catch (ContextOverflowException e) {
            throw new ContextOverflowException("model context window exceeded", null);
        } catch (MalformedOutputException e) {
            throw new MalformedOutputException("invalid model response", null);
        } catch (ModelException e) {
            throw new ModelException("model request failed", e.retryable(), e.status(), null, e.retryAfter());
        } catch (RuntimeException e) {
            throw new ModelException("model request failed", false);
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
