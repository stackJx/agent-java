package com.agentcore.domain.skill;

import java.util.List;
import java.util.Optional;

/** 技能目录（端口）。 */
public interface SkillCatalog {

    List<Skill> list();

    Optional<Skill> find(String name);

    /** 读取技能的完整说明正文。 */
    String instructions(Skill skill);
}
