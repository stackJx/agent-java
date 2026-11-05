/**
 * 技能端口：教 Agent 做某类事的说明书（只读）。
 *
 * <p>{@link com.agentcore.domain.skill.SkillCatalog} 由基础设施层从 {@code <目录>/<名字>/SKILL.md} 加载。
 * 系统提示词里只列名字和描述，模型需要时调用 {@code load_skill} 读取全文。
 */
package com.agentcore.domain.skill;
