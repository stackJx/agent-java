/**
 * 系统提示词组装。
 *
 * <p>{@link com.agentcore.application.prompt.SystemPromptBuilder} 按顺序拼接：基础人设、环境信息、额外要求、
 * 项目说明（{@code AGENTS.md}）、记忆索引、技能列表、MCP 说明。结果在会话开始时固定，保证提示缓存前缀稳定。
 */
package com.agentcore.application.prompt;
