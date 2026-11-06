package com.agentcore.spring.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 指标自动装配：类路径上有 Micrometer 时注册 {@link MicrometerRunObserver}，引擎会把它挂到每次运行上。
 * 没有 MeterRegistry 时它什么也不做。{@code agentcore.metrics.enabled=false} 关闭。
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBooleanProperty(name = "agentcore.metrics.enabled", matchIfMissing = true)
public class AgentCoreMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MicrometerRunObserver agentMicrometerRunObserver(ObjectProvider<MeterRegistry> registry) {
        return new MicrometerRunObserver(registry);
    }
}
