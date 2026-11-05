package com.agentcore.application.tool;

import com.agentcore.domain.skill.Skill;
import com.agentcore.domain.skill.SkillCatalog;
import com.agentcore.domain.tool.FunctionTool;
import com.agentcore.domain.tool.Schemas;
import com.agentcore.domain.tool.Tool;
import com.agentcore.domain.tool.ToolException;
import com.agentcore.domain.tool.ToolOutput;
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
                .handler((a, ctx) -> {
                    String name = (String) a.get("name");
                    Skill s = catalog.find(name).orElseThrow(() -> new ToolException("Unknown skill '" + name + "'. Available: "
                            + catalog.list().stream().map(Skill::name).collect(Collectors.joining(", "))));
                    return ToolOutput.text("# Skill: " + s.name() + "\nBase directory (for files the skill references): "
                            + s.location() + "\n\n" + catalog.instructions(s));
                }).build();
    }
}
