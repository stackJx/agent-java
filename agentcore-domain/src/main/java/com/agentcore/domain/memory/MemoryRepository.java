package com.agentcore.domain.memory;

import java.util.List;
import java.util.Optional;

/** 长期记忆仓储（端口）。 */
public interface MemoryRepository {

    List<MemoryEntry> list();

    Optional<MemoryEntry> find(String name);

    void save(MemoryEntry entry);

    boolean delete(String name);

    default List<MemoryEntry> search(String query, int limit) {
        String[] terms = query.toLowerCase().split("\\s+");
        record Hit(MemoryEntry e, long score) {
        }
        return list().stream().map(e -> {
                    String hay = (e.name() + " " + e.description() + " " + e.content()).toLowerCase();
                    long score = 0;
                    for (String t : terms) {
                        if (!t.isEmpty()) {
                            int idx = 0;
                            while ((idx = hay.indexOf(t, idx)) >= 0) {
                                score++;
                                idx += t.length();
                            }
                        }
                    }
                    return new Hit(e, score);
                }).filter(h -> h.score > 0).sorted((a, b) -> Long.compare(b.score, a.score)).limit(limit)
                .map(Hit::e).toList();
    }
}
