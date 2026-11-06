package com.agentcore.infrastructure.persistence;

import com.agentcore.domain.conversation.model.Conversation;
import com.agentcore.domain.conversation.model.ConversationId;
import com.agentcore.domain.conversation.model.ConversationSummary;
import com.agentcore.domain.conversation.repository.ConversationRepository;
import com.agentcore.domain.shared.model.DomainEvent;
import com.agentcore.domain.shared.util.BoundedLruCache;
import com.agentcore.infrastructure.json.DomainCodec;
import com.agentcore.infrastructure.json.Json;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 会话仓储：每个会话一个 JSONL 文件，<b>领域事件即存储格式</b>，只追加写入。
 * 加载时回放事件重建聚合；进程崩溃留下的半行会被跳过。
 * 写入成功后才把事件标记为已保存：写失败（例如磁盘满）时事件留在聚合里，下次保存一起补写。
 * 已加载的聚合缓存在身份映射里（最多 {@value #MAX_CACHED_CONVERSATIONS} 个），缓存期间同一会话在进程内只有一个实例。
 */
public final class JsonlConversationRepository implements ConversationRepository {

    private static final System.Logger LOG = System.getLogger(JsonlConversationRepository.class.getName());
    static final int MAX_CACHED_CONVERSATIONS = 200;

    private final Path root;
    private final BoundedLruCache<ConversationId, Conversation> identityMap = new BoundedLruCache<>(MAX_CACHED_CONVERSATIONS);

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
        List<DomainEvent> events = conversation.pendingEvents();
        identityMap.put(conversation.id(), conversation);
        if (events.isEmpty()) {
            return;
        }
        Path path = file(conversation.id());
        StringBuilder lines = new StringBuilder();
        for (DomainEvent event : events) {
            lines.append(Json.write(DomainCodec.event(event))).append('\n');
        }
        try {
            Files.createDirectories(root);
            if (endsWithPartialLine(path)) {
                lines.insert(0, '\n'); // 上次写到一半失败了：先换行，别让新事件接在半行后面一起变成坏行
            }
            Files.writeString(path, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist conversation " + conversation.id(), e);
        }
        conversation.markEventsSaved(events);
    }

    /** 文件不为空、最后一个字节又不是换行：说明上次只写了半行。 */
    private static boolean endsWithPartialLine(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return false;
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(file.length() - 1);
            return file.read() != '\n';
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

    /** 从最近修改的文件开始逐个加载，按 owner 过滤，凑够 limit 个就停。 */
    @Override
    public List<ConversationSummary> list(int limit, String owner) {
        List<ConversationSummary> summaries = new ArrayList<>();
        for (Path path : files()) {
            if (summaries.size() >= limit) {
                break;
            }
            try {
                Conversation conversation = findById(idOf(path)).orElseThrow();
                if (owner == null || Objects.equals(owner, conversation.owner())) {
                    summaries.add(new ConversationSummary(conversation.id(), conversation.title(), conversation.size(),
                            lastModified(path), conversation.usage()));
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "failed to read " + path);
            }
        }
        return summaries;
    }

    @Override
    public synchronized boolean delete(ConversationId id) {
        identityMap.remove(id);
        try {
            return Files.deleteIfExists(file(id));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete conversation " + id, e);
        }
    }

    /** 按文件的修改时间判断（每次保存都会追加写入，修改时间就是最后更新时间）。 */
    @Override
    public synchronized int deleteNotUpdatedSince(Instant cutoff, Set<ConversationId> keep) {
        int deleted = 0;
        for (Path path : files()) {
            if (!lastModified(path).isBefore(cutoff)) {
                continue;
            }
            try {
                ConversationId id = idOf(path);
                if (!keep.contains(id) && delete(id)) {
                    deleted++;
                }
            } catch (IllegalArgumentException notAConversationFile) {
                // 目录里混进来的、文件名不是会话 ID 的文件：不动它
            }
        }
        return deleted;
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
