package com.agentcore.infrastructure.persistence;

import com.agentcore.domain.skill.model.Skill;
import com.agentcore.domain.skill.repository.SkillCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 技能目录：扫描 {@code <dir>/<name>/SKILL.md}（frontmatter 写 name、description），与 Anthropic Agent Skills 约定一致。
 * 多个目录时先出现的优先。
 */
public final class FileSkillCatalog implements SkillCatalog {

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public FileSkillCatalog(List<Path> dirs) {
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> children = Files.list(dir)) {
                for (Path skillDir : children.sorted().toList()) {
                    Path skillFile = skillDir.resolve("SKILL.md");
                    if (Files.isRegularFile(skillFile)) {
                        Frontmatter.Doc doc = Frontmatter.parse(Files.readString(skillFile));
                        String name = String.valueOf(doc.meta().getOrDefault("name", skillDir.getFileName().toString()));
                        String description = String.valueOf(doc.meta().getOrDefault("description", "")).strip();
                        skills.putIfAbsent(name, new Skill(name, description, skillDir.toAbsolutePath().toString()));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public List<Skill> list() {
        return new ArrayList<>(skills.values());
    }

    @Override
    public Optional<Skill> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    @Override
    public String instructions(Skill skill) {
        try {
            return Frontmatter.parse(Files.readString(Path.of(skill.location(), "SKILL.md"))).body().strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
