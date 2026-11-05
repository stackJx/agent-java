package com.agentcore.domain.skill;

/**
 * Skill：按需加载的任务说明包。系统提示词里只放名称和描述，模型需要时再读取完整说明。
 *
 * @param location 技能所在目录（技能可附带脚本、模板等文件）
 */
public record Skill(String name, String description, String location) {
}
