package com.agentcore.infrastructure.persistence.jdbc;

import com.agentcore.domain.memory.model.MemoryEntry;
import com.agentcore.domain.memory.model.MemoryType;
import com.agentcore.domain.memory.repository.MemoryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;

/**
 * 长期记忆仓储（JDBC）。scope 用于隔离不同的记忆空间（例如按租户 / 按 Agent），默认 "default"。
 */
public final class JdbcMemoryRepository implements MemoryRepository {

    private final JdbcStore store;
    private final String table;
    private final String scope;

    public JdbcMemoryRepository(JdbcStore store, String scope) {
        this.store = store;
        this.table = store.table("memory");
        this.scope = scope == null || scope.isBlank() ? "default" : scope;
    }

    public JdbcMemoryRepository(JdbcStore store) {
        this(store, "default");
    }

    @Override
    public List<MemoryEntry> list() {
        return store.jdbc().sql("SELECT name, description, memory_type, content, updated_at FROM " + table + " WHERE scope_id = ? ORDER BY name")
                .param(scope)
                .query(JdbcMemoryRepository::entry)
                .list();
    }

    @Override
    public Optional<MemoryEntry> find(String name) {
        return store.jdbc().sql("SELECT name, description, memory_type, content, updated_at FROM " + table + " WHERE scope_id = ? AND name = ?")
                .params(scope, MemoryEntry.slug(name))
                .query(JdbcMemoryRepository::entry)
                .optional();
    }

    /** 先更新，没有这条再插入；插入撞上唯一约束说明别人刚插了，改为更新。 */
    @Override
    public void save(MemoryEntry entry) {
        store.inTransaction(() -> {
            if (update(entry) == 0) {
                try {
                    insert(entry);
                } catch (DataAccessException e) {
                    if (!JdbcStore.isIntegrityViolation(e)) {
                        throw e;
                    }
                    update(entry);
                }
            }
            return null;
        });
    }

    @Override
    public boolean delete(String name) {
        return store.inTransaction(() -> store.jdbc().sql("DELETE FROM " + table + " WHERE scope_id = ? AND name = ?")
                .params(scope, MemoryEntry.slug(name))
                .update() > 0);
    }

    private int update(MemoryEntry entry) {
        return store.jdbc().sql("UPDATE " + table + " SET description = ?, memory_type = ?, content = ?, updated_at = ? "
                        + "WHERE scope_id = ? AND name = ?")
                .params(entry.description(), entry.type().wire(), entry.content(), Timestamp.valueOf(entry.updated().atStartOfDay()),
                        scope, entry.name())
                .update();
    }

    private void insert(MemoryEntry entry) {
        store.jdbc().sql("INSERT INTO " + table + " (scope_id, name, description, memory_type, content, updated_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(scope, entry.name(), entry.description(), entry.type().wire(), entry.content(),
                        Timestamp.valueOf(entry.updated().atStartOfDay()))
                .update();
    }

    private static MemoryEntry entry(ResultSet row, int rowNumber) throws SQLException {
        Timestamp updatedAt = row.getTimestamp(5);
        LocalDate updated = updatedAt == null ? null : updatedAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return new MemoryEntry(row.getString(1), row.getString(2), MemoryType.parse(row.getString(3)), row.getString(4), updated);
    }
}
