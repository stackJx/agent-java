/**
 * 权限与审批：决定每次工具调用是允许、拒绝还是询问用户。
 *
 * <ul>
 *   <li>{@link com.agentcore.domain.permission.PermissionPolicy}：判定顺序 deny &gt; ask &gt; allow &gt; 模式默认值；对 bash 命令拆分子命令逐个检查；</li>
 *   <li>{@link com.agentcore.domain.permission.PermissionRule}：规则写法 {@code 工具名(匹配模式)}，例如 {@code bash(git status*)}；</li>
 *   <li>{@link com.agentcore.domain.permission.PermissionMode}：default / auto / readonly 三种模式；</li>
 *   <li>{@link com.agentcore.domain.permission.Approver}：审批端口，由宿主实现（命令行提问、Web 弹窗、IM 审批流）。</li>
 * </ul>
 */
package com.agentcore.domain.permission;
