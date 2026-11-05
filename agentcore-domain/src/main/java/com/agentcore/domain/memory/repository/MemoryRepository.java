package com.agentcore.domain.memory.repository;

import com.agentcore.domain.memory.model.MemoryEntry;
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
        record Hit(MemoryEntry entry, long score) {
        }
        return list().stream().map(entry -> {
                    String haystack = (entry.name() + " " + entry.description() + " " + entry.content()).toLowerCase();
                    long score = 0;
                    for (String term : terms) {
                        if (!term.isEmpty()) {
                            int idx = 0;
                            while ((idx = haystack.indexOf(term, idx)) >= 0) {
                                score++;
                                idx += term.length();
                            }
                        }
                    }
                    return new Hit(entry, score);
                }).filter(h -> h.score > 0).sorted((a, b) -> Long.compare(b.score, a.score)).limit(limit)
                .map(Hit::entry).toList();
    }
}
