/**
 * 权限判定：{@link com.agentcore.domain.permission.service.PermissionPolicy} 是接口（引擎只依赖它），
 * {@link com.agentcore.domain.permission.service.RulePermissionPolicy} 是默认实现：
 * 基于 allow / ask / deny 规则和权限模式，判定顺序 deny &gt; ask &gt; allow &gt; 模式默认值，bash 命令拆成子命令逐个检查。
 * 需要按租户、角色或公司权限系统判断时，自己实现 PermissionPolicy 替换掉默认实现。
 */
package com.agentcore.domain.permission.service;
