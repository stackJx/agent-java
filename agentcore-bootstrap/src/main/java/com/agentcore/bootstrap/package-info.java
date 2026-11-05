/**
 * 独立部署版（{@code bin/agentcore}）：一个基于 Spring Boot 的命令行与 HTTP 服务。
 *
 * <p>{@link com.agentcore.bootstrap.AgentCoreApplication} 只做三件事：把命令行参数翻译成 Spring 配置项（{@code CliArgs}）、
 * 按命令启动容器（{@code serve} 启动 Web，其余不启动）、执行命令。模型、工具、仓储、引擎的组装全部来自
 * {@code agentcore-spring-boot-starter} 的自动装配，和把 agentcore 嵌进自己的 Spring Boot 应用时完全一样。
 *
 * <p>配置：内置的 {@code agentcore-cli.yml} 依次导入 {@code ~/.agentcore/config.yaml}、{@code ./.agentcore/config.yaml}、
 * {@code ./agentcore.yaml}，格式就是 Spring Boot 的 application.yml。日志写 stderr（{@code logback-spring.xml}），
 * stdout 只留给答案和 {@code --events} 的输出。
 */
package com.agentcore.bootstrap;
