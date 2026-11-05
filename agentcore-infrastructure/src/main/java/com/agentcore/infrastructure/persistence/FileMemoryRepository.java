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
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().map(this::read).flatMap(Optional::stream).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<MemoryEntry> find(String name) {
        Path f = file(name);
        if (Files.exists(f)) {
            return read(f);
        }
        return list().stream().filter(e -> e.name().equals(name)).findFirst();
    }

    private Optional<MemoryEntry> read(Path f) {
        try {
            Frontmatter.Doc d = Frontmatter.parse(Files.readString(f));
            String fname = f.getFileName().toString();
            String name = String.valueOf(d.meta().getOrDefault("name", fname.substring(0, fname.length() - 3)));
            LocalDate updated = null;
            try {
                updated = d.meta().get("updated") == null ? null : LocalDate.parse(String.valueOf(d.meta().get("updated")));
            } catch (RuntimeException ignored) {
                // 格式不对就忽略
            }
            return Optional.of(new MemoryEntry(name, String.valueOf(d.meta().getOrDefault("description", "")),
                    MemoryType.parse(String.valueOf(d.meta().getOrDefault("type", "note"))), d.body(), updated));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(MemoryEntry e) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", e.name());
        meta.put("description", e.description());
        meta.put("type", e.type().wire());
        meta.put("updated", e.updated().toString());
        try {
            Files.createDirectories(root);
            Files.writeString(file(e.name()), Frontmatter.render(meta, e.content()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public boolean delete(String name) {
        Optional<MemoryEntry> e = find(name);
        if (e.isEmpty()) {
            return false;
        }
        try {
            return Files.deleteIfExists(file(e.get().name()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
