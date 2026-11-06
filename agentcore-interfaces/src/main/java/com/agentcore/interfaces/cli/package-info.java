/**
 * 交互式命令行。
 *
 * <ul>
 *   <li>{@link com.agentcore.interfaces.cli.ChatRepl}：读输入、处理斜杠命令（/new /compact /resume ……）、调用 {@code AgentService}；</li>
 *   <li>{@link com.agentcore.interfaces.cli.ConsoleRenderer}：把 {@code AgentEvent} 渲染到终端，正文写 stdout、过程信息写 stderr；</li>
 *   <li>{@link com.agentcore.interfaces.cli.ConsoleApprover} / {@link com.agentcore.interfaces.cli.ConsolePrompter}：命令行里的审批和提问；</li>
 *   <li>{@link com.agentcore.interfaces.cli.ConsoleIO}：读输入。真实终端用 JLine（行编辑、历史、Tab 补全命令，运行中 Ctrl+C 取消当前任务），
 *       管道 / 重定向时按行读取。</li>
 * </ul>
 */
package com.agentcore.interfaces.cli;
