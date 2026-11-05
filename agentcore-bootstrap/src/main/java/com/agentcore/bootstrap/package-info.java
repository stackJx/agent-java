/**
 * 独立部署版的启动与组装。
 *
 * <ul>
 *   <li>{@link com.agentcore.bootstrap.AgentCoreApplication}：{@code main} 入口，解析命令行参数，启动交互模式、单次运行或 HTTP 服务；</li>
 *   <li>{@link com.agentcore.bootstrap.RuntimeAssembler}：把配置变成一个可用的运行时：创建模型、注册工具、选择仓储、组装引擎。
 *       想了解"各部分怎么拼到一起"，读它的 {@code assemble} 方法。</li>
 * </ul>
 */
package com.agentcore.bootstrap;
