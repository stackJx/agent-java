package com.agentcore.infrastructure.persistence.jdbc;

import com.agentcore.domain.memory.MemoryEntry;
import com.agentcore.domain.memory.MemoryRepository;
import com.agentcore.domain.memory.MemoryType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        return store.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT name, description, memory_type, content, updated_at FROM "
                    + table + " WHERE scope_id = ? ORDER BY name")) {
                ps.setString(1, scope);
                return read(ps);
            }
        });
    }

    @Override
    public Optional<MemoryEntry> find(String name) {
        return store.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT name, description, memory_type, content, updated_at FROM "
                    + table + " WHERE scope_id = ? AND name = ?")) {
                ps.setString(1, scope);
                ps.setString(2, MemoryEntry.slug(name));
                return read(ps).stream().findFirst();
            }
        });
    }

    @Override
    public void save(MemoryEntry e) {
        store.inTransaction(conn -> {
            if (update(conn, e) == 0) {
                try {
                    insert(conn, e);
                } catch (SQLException ex) {
                    if (!JdbcStore.isIntegrityViolation(ex)) {
                        throw ex;
                    }
                    update(conn, e); // 并发插入：改为更新
                }
            }
            return null;
        });
    }

    @Override
    public boolean delete(String name) {
        return store.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE scope_id = ? AND name = ?")) {
                ps.setString(1, scope);
                ps.setString(2, MemoryEntry.slug(name));
                return ps.executeUpdate() > 0;
            }
        });
    }

    private int update(Connection conn, MemoryEntry e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE " + table
                + " SET description = ?, memory_type = ?, content = ?, updated_at = ? WHERE scope_id = ? AND name = ?")) {
            ps.setString(1, e.description());
            ps.setString(2, e.type().wire());
            ps.setString(3, e.content());
            ps.setTimestamp(4, Timestamp.valueOf(e.updated().atStartOfDay()));
            ps.setString(5, scope);
            ps.setString(6, e.name());
            return ps.executeUpdate();
        }
    }

    private void insert(Connection conn, MemoryEntry e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + table
                + " (scope_id, name, description, memory_type, content, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, scope);
            ps.setString(2, e.name());
            ps.setString(3, e.description());
            ps.setString(4, e.type().wire());
            ps.setString(5, e.content());
            ps.setTimestamp(6, Timestamp.valueOf(e.updated().atStartOfDay()));
            ps.executeUpdate();
        }
    }

    private static List<MemoryEntry> read(PreparedStatement ps) throws SQLException {
        List<MemoryEntry> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp(5);
                LocalDate updated = ts == null ? null : ts.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                out.add(new MemoryEntry(rs.getString(1), rs.getString(2), MemoryType.parse(rs.getString(3)), rs.getString(4), updated));
            }
        }
        return out;
    }
}
