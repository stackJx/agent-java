package com.agentcore.spring.web;

import com.agentcore.application.AgentService;
import com.agentcore.application.approval.AsyncApprover;
import com.agentcore.domain.permission.gateway.Approver;
import com.agentcore.interfaces.web.AgentController;
import com.agentcore.interfaces.web.AgentUiController;
import com.agentcore.interfaces.web.BearerTokenInterceptor;
import com.agentcore.interfaces.web.LocalOnlyInterceptor;
import com.agentcore.interfaces.web.ModelCatalog;
import com.agentcore.interfaces.web.ModelController;
import com.agentcore.spring.AgentCoreAutoConfiguration;
import com.agentcore.spring.AgentCoreProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.resource.PathResourceResolver;

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
    public AgentController agentController(AgentService service, ObjectProvider<AsyncApprover> approver,
                                           AgentCoreProperties props, ObjectProvider<ModelCatalog> models) {
        return new AgentController(service, approver.getIfAvailable(), props.getWeb().getSseTimeout(), models.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(ModelCatalog.class)
    @ConditionalOnBooleanProperty(name = "agentcore.web.models.enabled", matchIfMissing = true)
    public ModelCatalog agentModelCatalog(AgentService service, AgentCoreProperties props) {
        return new FileModelCatalog(service.engine().model(), modelSettingsFile(props.getWeb().getModels().getFile()));
    }

    /** 配置的文件路径（支持 ~/ 开头）；没配置时返回 null，模型配置只保存在内存里。 */
    private static Path modelSettingsFile(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String expanded = configured.startsWith("~/") ? System.getProperty("user.home") + configured.substring(1) : configured;
        return Path.of(expanded);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "agentcore.web.models.enabled", matchIfMissing = true)
    public ModelController agentModelController(ModelCatalog catalog) {
        return new ModelController(catalog);
    }

    /** 自带的 Web 页面（默认 /agentcore/）。agentcore.web.ui.enabled=false 关闭。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "agentcore.web.ui.enabled", matchIfMissing = true)
    public AgentUiController agentUiController(AgentCoreProperties props) {
        return new AgentUiController(props.getWeb().getBasePath());
    }

    /** 页面的静态资源（css、js、图标）放在 jar 的 agentcore-ui/ 下；访问 / 时可以跳转到页面。 */
    @Bean
    @ConditionalOnBooleanProperty(name = "agentcore.web.ui.enabled", matchIfMissing = true)
    public WebMvcConfigurer agentUiResources(AgentCoreProperties props) {
        String uiPath = props.getWeb().getUi().getPath();
        String apiPath = props.getWeb().getBasePath();
        if (uiPath.equals(apiPath) || uiPath.startsWith(apiPath + "/") || apiPath.startsWith(uiPath + "/")) {
            throw new IllegalArgumentException("agentcore.web.ui.path and agentcore.web.base-path must not overlap");
        }
        boolean redirectRoot = props.getWeb().getUi().isRedirectRoot();
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(uiPath + "/**")
                        .addResourceLocations("classpath:/agentcore-ui/")
                        .setCacheControl(CacheControl.noCache()) // 允许缓存，但每次使用前重新校验
                        .resourceChain(false)
                        .addResolver(new PathResourceResolver() {
                            @Override
                            protected Resource getResource(String path, Resource location) throws IOException {
                                // index 只能由控制器渲染，连 index.html/ 等静态路径变体也不能返回模板。
                                if ("index.html".equals(StringUtils.getFilename(path.replaceAll("/+$", "")))) {
                                    return null;
                                }
                                return super.getResource(path, location);
                            }
                        });
            }

            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                // RedirectView 默认包含 servlet context-path，且不把入口的查询参数传给目标。
                registry.addRedirectViewController(uiPath, uiPath + "/");
                if (redirectRoot) {
                    registry.addRedirectViewController("/", uiPath + "/");
                }
            }
        };
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
        String base = props.getWeb().getBasePath();
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
