package com.agentcore.infrastructure.persistence;

import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.shared.model.DomainEvent;
import com.agentcore.infrastructure.json.DomainCodec;
import com.agentcore.infrastructure.json.Json;
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
    public synchronized void save(Conversation conversation) {
        List<DomainEvent> events = conversation.pullEvents();
        identityMap.put(conversation.id(), conversation);
        if (events.isEmpty()) {
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (DomainEvent event : events) {
            lines.append(Json.write(DomainCodec.event(event))).append('\n');
        }
        try {
            Files.createDirectories(root);
            Files.writeString(file(conversation.id()), lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist conversation " + conversation.id(), e);
        }
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        Conversation cached = identityMap.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path path = file(id);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Conversation loaded = load(path, id);
        Conversation raced = identityMap.putIfAbsent(id, loaded); // 别的线程刚加载过就用它的
        return Optional.of(raced != null ? raced : loaded);
    }

    /** 逐行读事件重放；坏行（例如写到一半时进程被杀）跳过，不影响其余历史。 */
    private Conversation load(Path path, ConversationId id) {
        List<DomainEvent> events = new ArrayList<>();
        try (Stream<String> lines = Files.lines(path)) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    DomainEvent event = DomainCodec.event(Json.read(line), id);
                    if (event != null) {
                        events.add(event);
                    }
                } catch (RuntimeException corrupt) {
                    LOG.log(System.Logger.Level.WARNING, "skipping corrupt line in " + path);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Conversation.replay(events);
    }

    @Override
    public Optional<Conversation> findLatest() {
        return files().stream().findFirst().map(JsonlConversationRepository::idOf).flatMap(this::findById);
    }

    @Override
    public List<ConversationSummary> list(int limit) {
        List<ConversationSummary> summaries = new ArrayList<>();
        for (Path path : files().stream().limit(limit).toList()) {
            try {
                Conversation conversation = findById(idOf(path)).orElseThrow();
                summaries.add(new ConversationSummary(conversation.id(), conversation.title(), conversation.size(), lastModified(path),
                        conversation.usage()));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "failed to read " + path);
            }
        }
        return summaries;
    }

    private List<Path> files() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(JsonlConversationRepository::lastModified).reversed()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ConversationId idOf(Path path) {
        String fileName = path.getFileName().toString();
        return ConversationId.of(fileName.substring(0, fileName.length() - ".jsonl".length()));
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
