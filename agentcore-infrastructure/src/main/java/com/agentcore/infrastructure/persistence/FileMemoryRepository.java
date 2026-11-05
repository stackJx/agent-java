package com.agentcore.infrastructure.persistence;

import com.agentcore.domain.memory.model.MemoryEntry;
import com.agentcore.domain.memory.model.MemoryType;
import com.agentcore.domain.memory.repository.MemoryRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** 长期记忆：每条一个带 frontmatter 的 Markdown 文件，人可以直接查看、编辑、纳入版本管理。 */
public final class FileMemoryRepository implements MemoryRepository {

    private final Path root;

    public FileMemoryRepository(Path root) {
        this.root = root;
    }

    private Path file(String name) {
        return root.resolve(MemoryEntry.slug(name) + ".md");
    }

    @Override
    public List<MemoryEntry> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(file -> file.toString().endsWith(".md")).sorted().map(this::read).flatMap(Optional::stream).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<MemoryEntry> find(String name) {
        Path path = file(name);
        if (Files.exists(path)) {
            return read(path);
        }
        // 文件名是 slug，手工建的文件名字可能对不上，再按 frontmatter 里的 name 找一遍
        return list().stream().filter(entry -> entry.name().equals(name)).findFirst();
    }

    /** 一个 Markdown 文件：frontmatter 里是 name / description / type / updated，正文是记忆内容。 */
    private Optional<MemoryEntry> read(Path path) {
        try {
            Frontmatter.Doc doc = Frontmatter.parse(Files.readString(path));
            Map<String, Object> meta = doc.meta();
            String fileName = path.getFileName().toString();
            String name = String.valueOf(meta.getOrDefault("name", fileName.substring(0, fileName.length() - ".md".length())));
            LocalDate updated = null;
            try {
                updated = meta.get("updated") == null ? null : LocalDate.parse(String.valueOf(meta.get("updated")));
            } catch (RuntimeException badDate) {
                // 格式不对就忽略
            }
            return Optional.of(new MemoryEntry(name, String.valueOf(meta.getOrDefault("description", "")),
                    MemoryType.parse(String.valueOf(meta.getOrDefault("type", "note"))), doc.body(), updated));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(MemoryEntry entry) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", entry.name());
        meta.put("description", entry.description());
        meta.put("type", entry.type().wire());
        meta.put("updated", entry.updated().toString());
        try {
            Files.createDirectories(root);
            Files.writeString(file(entry.name()), Frontmatter.render(meta, entry.content()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean delete(String name) {
        Optional<MemoryEntry> entry = find(name);
        if (entry.isEmpty()) {
            return false;
        }
        try {
            return Files.deleteIfExists(file(entry.get().name()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
