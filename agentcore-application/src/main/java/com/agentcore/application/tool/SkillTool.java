package com.agentcore.application.tool;

import com.agentcore.domain.skill.model.Skill;
import com.agentcore.domain.skill.repository.SkillCatalog;
import com.agentcore.domain.tool.exception.ToolException;
import com.agentcore.domain.tool.model.FunctionTool;
import com.agentcore.domain.tool.model.Tool;
import com.agentcore.domain.tool.model.ToolOutput;
import com.agentcore.domain.tool.schema.Schemas;
import java.util.stream.Collectors;

/** load_skill：按需读取技能的完整说明。 */
public final class SkillTool {

    private SkillTool() {
    }

    public static Tool create(SkillCatalog catalog) {
        return FunctionTool.builder("load_skill")
                .description("Load the full instructions of a skill listed in the system prompt.")
                .schema(Schemas.object().string("name", "Skill name.", true).build())
                .readOnly(true)
                .handler((args, ctx) -> {
                    String name = (String) args.get("name");
                    Skill skill = catalog.find(name).orElseThrow(() -> new ToolException("Unknown skill '" + name + "'. Available: "
                            + catalog.list().stream().map(Skill::name).collect(Collectors.joining(", "))));
                    return ToolOutput.text("# Skill: " + skill.name() + "\nBase directory (for files the skill references): "
                            + skill.location() + "\n\n" + catalog.instructions(skill));
                }).build();
    }
}
