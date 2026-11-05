package com.agentcore.spring.web;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.permission.Approver;
import com.agentcore.spring.AgentCoreAutoConfiguration;
import com.agentcore.spring.AgentCoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Web 接口自动装配：Servlet 应用且引入了 Spring MVC 时，注册 {@link AgentController}（HTTP + SSE），
 * 并在应用没有自定义 {@link Approver} 时提供基于 SSE 的 Web 审批（{@link AsyncApprover}）。
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
}
