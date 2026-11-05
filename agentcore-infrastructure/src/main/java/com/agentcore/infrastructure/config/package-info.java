/**
 * 独立部署版的配置：{@link com.agentcore.infrastructure.config.Settings} 合并内置默认值与各级 YAML
 * （{@code ~/.agentcore/config.yaml} → {@code ./.agentcore/config.yaml} → {@code ./agentcore.yaml} → {@code --config}），
 * 替换 {@code ${ENV}} 环境变量，按点路径读取。Spring Boot 版不用它，改用 {@code AgentCoreProperties}。
 */
package com.agentcore.infrastructure.config;
