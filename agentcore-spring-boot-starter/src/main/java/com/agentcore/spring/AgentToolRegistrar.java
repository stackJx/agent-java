package com.agentcore.spring;

import com.agentcore.application.subagent.TaskTool;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolRegistry;
import com.agentcore.infrastructure.tool.annotation.AnnotatedTools;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * 所有单例创建完成后，扫描容器：
 * <ul>
 *   <li>{@link Tool} 类型的 Bean 直接注册；</li>
 *   <li>带 {@code @AgentTool} 方法的 Bean 生成工具（按原始类型扫描注解，兼容 AOP 代理）。</li>
 * </ul>
 * 工具表是线程安全的可变注册表，启动阶段注册完成后才会有请求进来。
 */
public class AgentToolRegistrar implements SmartInitializingSingleton, ApplicationContextAware {

    private ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.context = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ToolRegistry registry = context.getBeanProvider(ToolRegistry.class).getIfAvailable();
        if (registry == null) {
            return;
        }
        ConfigurableListableBeanFactory bf = context instanceof ConfigurableApplicationContext cac ? cac.getBeanFactory() : null;
        for (String name : context.getBeanDefinitionNames()) {
            if (bf != null && bf.containsBeanDefinition(name) && !bf.getBeanDefinition(name).isSingleton()) {
                continue;
            }
            Class<?> type = context.getType(name, false);
            if (type == null) {
                continue;
            }
            Class<?> userType = ClassUtils.getUserClass(type);
            if (TaskTool.class.isAssignableFrom(type)) {
                continue; // 由 ToolRegistry Bean 按 subagent-max-depth / tools 白名单决定是否注册
            }
            if (Tool.class.isAssignableFrom(type)) {
                Tool t = (Tool) context.getBean(name);
                if (!registry.contains(t.name())) {
                    registry.register(t);
                }
            } else if (AnnotatedTools.hasTools(userType)) {
                registry.registerAll(AnnotatedTools.from(context.getBean(name), userType));
            }
        }
    }
}
