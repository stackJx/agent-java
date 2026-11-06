/**
 * 内置工具：文件读写（{@link com.agentcore.infrastructure.tool.builtin.FileTools}）、
 * 按文件名和内容搜索（{@link com.agentcore.infrastructure.tool.builtin.SearchTools}）、
 * 命令执行（{@link com.agentcore.infrastructure.tool.builtin.ShellTools}）、抓网页（{@link com.agentcore.infrastructure.tool.builtin.WebTools}）、
 * 任务清单和向用户提问（{@link com.agentcore.infrastructure.tool.builtin.InteractionTools}）。
 * {@link com.agentcore.infrastructure.tool.builtin.BuiltinTools} 按名字挑选启用哪些。
 *
 * <p>资源上限：子进程输出用 {@code HeadTailBuffer} 只保留头尾，文本文件和搜索结果用 {@code CappedLineReader} 逐行读、
 * 截断超长的行 —— 文件再大、输出再多也不会撑爆内存。
 */
package com.agentcore.infrastructure.tool.builtin;
