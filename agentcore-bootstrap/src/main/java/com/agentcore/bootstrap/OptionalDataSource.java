package com.agentcore.bootstrap;

import java.util.Map;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 没配置 spring.datasource.url 时排除数据源自动装配：Spring Boot 在 classpath 上有 JDBC 却没有地址时会启动失败，
 * 而命令行默认用文件存储，不需要数据库。
 */
final class OptionalDataSource implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String DATASOURCE_AUTO_CONFIG = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        if (env.containsProperty("spring.datasource.url")) {
            return;
        }
        String existing = env.getProperty("spring.autoconfigure.exclude", "");
        String excluded = existing.isBlank() ? DATASOURCE_AUTO_CONFIG : existing + "," + DATASOURCE_AUTO_CONFIG;
        env.getPropertySources().addFirst(new MapPropertySource("agentcoreOptionalDataSource",
                Map.of("spring.autoconfigure.exclude", excluded)));
    }
}
