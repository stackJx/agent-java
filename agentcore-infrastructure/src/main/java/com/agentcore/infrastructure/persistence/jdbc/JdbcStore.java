package com.agentcore.infrastructure.persistence.jdbc;

import com.agentcore.domain.shared.ConcurrencyConflictException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * JDBC 存储配置：数据源 + 平台 + 表名前缀，以及事务执行的小工具。
 *
 * @param tablePrefix 表名前缀（默认 agent_），只允许字母数字下划线
 */
public record JdbcStore(DataSource dataSource, JdbcPlatform platform, String tablePrefix) {

    public JdbcStore {
        Objects.requireNonNull(dataSource, "dataSource");
        tablePrefix = tablePrefix == null ? "agent_" : tablePrefix;
        if (!tablePrefix.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid table prefix: " + tablePrefix);
        }
        if (platform == null) {
            platform = JdbcPlatform.detect(dataSource);
        }
    }

    public static JdbcStore of(DataSource dataSource) {
        return new JdbcStore(dataSource, null, "agent_");
    }

    public String table(String name) {
        return tablePrefix + name;
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** 在一个事务里执行；失败回滚。 */
    public <T> T inTransaction(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    /** 只读查询（自动提交模式）。 */
    public <T> T query(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    static boolean isIntegrityViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof SQLException se && se.getSQLState() != null && se.getSQLState().startsWith("23")) {
                return true;
            }
            if (t instanceof SQLException se && se.getMessage() != null && se.getMessage().contains("SQLITE_CONSTRAINT")) {
                return true; // SQLite 驱动不填 SQLState
            }
        }
        return false;
    }

    static RuntimeException translate(SQLException e) {
        if (isIntegrityViolation(e)) {
            return new ConcurrencyConflictException("concurrent modification detected: " + e.getMessage());
        }
        return new JdbcStoreException(e.getMessage(), e);
    }

    /** 数据库访问失败。 */
    public static final class JdbcStoreException extends RuntimeException {
        public JdbcStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
