package com.agentcore.spring.web;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.interfaces.web.AgentController;
import com.agentcore.interfaces.web.BearerTokenInterceptor;
import com.agentcore.interfaces.web.LocalOnlyInterceptor;
import com.agentcore.spring.AgentCoreAutoConfiguration;
import com.agentcore.spring.AgentCoreProperties;
import java.util.Map;
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
 * 配置了 {@code agentcore.web.token} 时挂上 {@link BearerTokenInterceptor}；没有配置时默认挂上 {@link LocalOnlyInterceptor}
 * （只接受本机请求，{@code agentcore.web.local-only=false} 关闭）。
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

    /**
     * 访问控制：只拦截 agentcore 自己的接口，health 放行。
     * 配置了共享 token 或用户 token 就要求 Bearer token；都没配置时默认只接受本机请求。
     */
    @Bean
    public WebMvcConfigurer agentTokenConfigurer(AgentCoreProperties props) {
        String token = props.getWeb().getToken();
        Map<String, String> userTokens = props.getWeb().getUserTokens();
        boolean hasToken = (token != null && !token.isBlank()) || !userTokens.isEmpty();
        boolean localOnly = !hasToken && props.getWeb().isLocalOnly();
        String base = props.getWeb().getBasePath().replaceAll("/+$", "");
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                if (hasToken) {
                    registry.addInterceptor(new BearerTokenInterceptor(token, userTokens))
                            .addPathPatterns(base + "/**")
                            .excludePathPatterns(base + "/health");
                } else if (localOnly) {
                    registry.addInterceptor(new LocalOnlyInterceptor())
                            .addPathPatterns(base + "/**")
                            .excludePathPatterns(base + "/health");
                }
            }
        };
    }
}
