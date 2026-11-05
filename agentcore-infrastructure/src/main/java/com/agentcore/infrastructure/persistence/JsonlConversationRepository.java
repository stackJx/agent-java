package com.agentcore.infrastructure.persistence;

import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.shared.model.DomainEvent;
import com.agentcore.infrastructure.json.DomainCodec;
import com.agentcore.infrastructure.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 会话仓储：每个会话一个 JSONL 文件，<b>领域事件即存储格式</b>，只追加写入。
 * 加载时回放事件重建聚合；进程崩溃留下的半行会被跳过。
 * 已加载的聚合缓存在身份映射里，同一会话在进程内只有一个实例（聚合方法本身是线程安全的）。
 */
public final class JsonlConversationRepository implements ConversationRepository {

    private static final System.Logger LOG = System.getLogger(JsonlConversationRepository.class.getName());

    private final Path root;
    private final Map<ConversationId, Conversation> identityMap = new ConcurrentHashMap<>();

    public JsonlConversationRepository(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    private Path file(ConversationId id) {
        return root.resolve(id.value() + ".jsonl");
    }

    @Override
    public synchronized void save(Conversation c) {
        List<DomainEvent> events = c.pullEvents();
        identityMap.put(c.id(), c);
        if (events.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (DomainEvent e : events) {
            sb.append(Json.write(DomainCodec.event(e))).append('\n');
        }
        try {
            Files.createDirectories(root);
            Files.writeString(file(c.id()), sb, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist conversation " + c.id(), e);
        }
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        Conversation cached = identityMap.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path f = file(id);
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        Conversation c = load(f, id);
        Conversation prev = identityMap.putIfAbsent(id, c);
        return Optional.of(prev != null ? prev : c);
    }

    private Conversation load(Path f, ConversationId id) {
        List<DomainEvent> events = new ArrayList<>();
        try (Stream<String> lines = Files.lines(f)) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    JsonNode n = Json.read(line);
                    DomainEvent e = DomainCodec.event(n, id);
                    if (e != null) {
                        events.add(e);
                    }
                } catch (RuntimeException ex) {
                    LOG.log(System.Logger.Level.WARNING, "skipping corrupt line in " + f);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Conversation.replay(events);
    }

    @Override
    public Optional<Conversation> findLatest() {
        return files().stream().findFirst().map(f -> idOf(f)).flatMap(this::findById);
    }

    @Override
    public List<ConversationSummary> list(int limit) {
        List<ConversationSummary> out = new ArrayList<>();
        for (Path f : files().stream().limit(limit).toList()) {
            try {
                Conversation c = findById(idOf(f)).orElseThrow();
                out.add(new ConversationSummary(c.id(), c.title(), c.size(), lastModified(f), c.usage()));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "failed to read " + f);
            }
        }
        return out;
    }

    private List<Path> files() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(JsonlConversationRepository::lastModified).reversed()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ConversationId idOf(Path f) {
        String n = f.getFileName().toString();
        return ConversationId.of(n.substring(0, n.length() - ".jsonl".length()));
    }

    private static Instant lastModified(Path f) {
        try {
            return Files.getLastModifiedTime(f).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
