package com.agentcore.spring.web;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.permission.Approver;
import com.agentcore.interfaces.web.AgentController;
import com.agentcore.interfaces.web.BearerTokenInterceptor;
import com.agentcore.spring.AgentCoreAutoConfiguration;
import com.agentcore.spring.AgentCoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Web 接口自动装配：Servlet 应用且引入了 Spring MVC 时，注册接口层的 {@link AgentController}（HTTP + SSE），
 * 并在应用没有自定义 {@link Approver} 时提供基于 SSE 的 Web 审批（{@link AsyncApprover}）。
 * 配置了 {@code agentcore.web.token} 时，再挂上 {@link BearerTokenInterceptor}。
 */
@AutoConfiguration(after = AgentCoreAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(SseEmitter.class)
@ConditionalOnBooleanProperty(name = "agentcore.web.enabled", matchIfMissing = true)
public class AgentCoreWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Approver.class)
    public AsyncApprover agentAsyncApprover(AgentCoreProperties props) {
        return new AsyncApprover(props.getWeb().getApprovalTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentController agentController(AgentService service, ObjectProvider<AsyncApprover> approver, AgentCoreProperties props) {
        return new AgentController(service, approver.getIfAvailable(), props.getWeb().getSseTimeout());
    }

    /** token 鉴权：只拦截 agentcore 自己的接口，health 放行；token 为空时不启用。 */
    @Bean
    public WebMvcConfigurer agentTokenConfigurer(AgentCoreProperties props) {
        String token = props.getWeb().getToken();
        String base = props.getWeb().getBasePath().replaceAll("/+$", "");
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                if (token != null && !token.isBlank()) {
                    registry.addInterceptor(new BearerTokenInterceptor(token))
                            .addPathPatterns(base + "/**")
                            .excludePathPatterns(base + "/health");
                }
            }
        };
    }
}
