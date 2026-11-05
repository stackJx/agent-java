/**
 * 模型端口：{@link com.agentcore.domain.llm.gateway.ModelGateway} 及回调 {@link com.agentcore.domain.llm.gateway.ModelStreamListener}，由基础设施层为每家厂商实现。
 * {@link com.agentcore.domain.llm.gateway.RetryingModelGateway} 是包在端口外面的重试装饰器（引擎自动套上），追踪、监控也可以照这个方式再包一层。
 */
package com.agentcore.domain.llm.gateway;
