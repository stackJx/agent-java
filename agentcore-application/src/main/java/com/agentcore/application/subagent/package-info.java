/**
 * 子 Agent。
 *
 * <p>{@link com.agentcore.application.subagent.TaskTool} 是一个工具：模型调用它时，复制父引擎（同一套权限、审批、钩子），
 * 按角色过滤工具、换上角色自己的提示词和模型，在一段全新的会话里运行，只把最终报告交回。
 * 角色定义在 {@link com.agentcore.application.subagent.SubAgentProfile}。
 */
package com.agentcore.application.subagent;
