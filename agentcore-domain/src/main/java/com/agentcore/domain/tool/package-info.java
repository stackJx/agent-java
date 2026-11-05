/**
 * 工具：模型能调用的能力。
 *
 * <ul>
 *   <li>{@link com.agentcore.domain.tool.Tool}：工具接口。{@code readOnly} 决定默认是否免审批、能否与其他只读工具并行；</li>
 *   <li>{@link com.agentcore.domain.tool.FunctionTool}：用 builder + lambda 定义工具（内置工具都这样写）；</li>
 *   <li>{@link com.agentcore.domain.tool.ToolRegistry}：注册表，顺序稳定（工具列表是提示缓存前缀的一部分）；</li>
 *   <li>{@link com.agentcore.domain.tool.SchemaValidator}：执行前按 JSON Schema 校验参数，并做温和的类型转换；</li>
 *   <li>{@link com.agentcore.domain.tool.ToolContext}：执行时能拿到的工作目录、进度上报、会话级状态、取消信号；</li>
 *   <li>{@link com.agentcore.domain.tool.ToolException}：抛出它表示"模型可以自己修正的错误"，消息原样交给模型。</li>
 * </ul>
 * 一次工具调用经过的全部检查见 {@code docs/04-tools-and-permissions.md}。
 */
package com.agentcore.domain.tool;
