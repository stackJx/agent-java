/**
 * 长期记忆端口：跨会话保存的事实、偏好、项目背景。
 *
 * <p>{@link com.agentcore.domain.memory.MemoryRepository} 由基础设施层实现（Markdown 文件或数据库）；
 * 模型通过应用层的记忆工具（{@code memory_save} 等）自己读写，会话开始时记忆的名字和描述会写进系统提示词。
 */
package com.agentcore.domain.memory;
