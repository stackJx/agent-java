package com.agentcore.domain.memory;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 一条长期记忆（跨会话保存的事实 / 偏好 / 项目背景）。
 *
 * @param name 短的 kebab-case 标识，作为唯一键
 */
public record MemoryEntry(String name, String description, MemoryType type, String content, LocalDate updated) {

    public MemoryEntry {
        Objects.requireNonNull(name, "name");
        name = slug(name);
        description = description == null ? "" : description.replace('\n', ' ').strip();
        type = type == null ? MemoryType.NOTE : type;
        content = content == null ? "" : content.strip();
        updated = updated == null ? LocalDate.now() : updated;
    }

    public static String slug(String name) {
        String s = name.strip().toLowerCase().replaceAll("[^\\p{L}\\p{N}_-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "untitled" : s.length() > 80 ? s.substring(0, 80) : s;
    }
}
