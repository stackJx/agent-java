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
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                for (Path sub : s.sorted().toList()) {
                    Path md = sub.resolve("SKILL.md");
                    if (Files.isRegularFile(md)) {
                        Frontmatter.Doc doc = Frontmatter.parse(Files.readString(md));
                        String name = String.valueOf(doc.meta().getOrDefault("name", sub.getFileName().toString()));
                        String desc = String.valueOf(doc.meta().getOrDefault("description", "")).strip();
                        skills.putIfAbsent(name, new Skill(name, desc, sub.toAbsolutePath().toString()));
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
