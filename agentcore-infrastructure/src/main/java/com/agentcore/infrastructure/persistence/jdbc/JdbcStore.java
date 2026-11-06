package com.agentcore.infrastructure.persistence.jdbc;

import com.agentcore.domain.shared.exception.ConcurrencyConflictException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC 存储配置：数据源 + 平台 + 表名前缀，以及基于 Spring JDBC 的读写入口。
 *
 * <ul>
 *   <li>{@link #jdbc()}：Spring 的 {@link JdbcClient}，写 SQL、绑参数、映射结果；连接的获取和释放由它负责；</li>
 *   <li>{@link #inTransaction}：在一个事务里执行（{@link TransactionTemplate}），抛异常就回滚；</li>
 *   <li>唯一约束冲突统一翻译成 {@link ConcurrencyConflictException}（乐观并发的兜底）。</li>
 * </ul>
 */
public final class JdbcStore {

    private final DataSource dataSource;
    private final JdbcPlatform platform;
    private final String tablePrefix;
    private final JdbcTemplate template;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    /**
     * @param platform    null 表示从连接元数据自动识别
     * @param tablePrefix 表名前缀（默认 agent_），只允许字母数字下划线
     */
    public JdbcStore(DataSource dataSource, JdbcPlatform platform, String tablePrefix) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tablePrefix = tablePrefix == null ? "agent_" : tablePrefix;
        if (!this.tablePrefix.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid table prefix: " + this.tablePrefix);
        }
        this.platform = platform != null ? platform : JdbcPlatform.detect(dataSource);
        this.template = new JdbcTemplate(dataSource);
        this.jdbc = JdbcClient.create(template);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public static JdbcStore of(DataSource dataSource) {
        return new JdbcStore(dataSource, null, "agent_");
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcPlatform platform() {
        return platform;
    }

    public String tablePrefix() {
        return tablePrefix;
    }

    public String table(String name) {
        return tablePrefix + name;
    }

    /** 写 SQL 的入口。在 {@link #inTransaction} 里调用时自动加入当前事务。 */
    public JdbcClient jdbc() {
        return jdbc;
    }

    /** 执行一条 DDL（建表、建索引）。 */
    public void execute(String sql) {
        template.execute(sql);
    }

    /** 批量执行同一条语句（JdbcClient 没有批量接口）。 */
    public void batchUpdate(String sql, List<Object[]> rows) {
        translateErrors(() -> template.batchUpdate(sql, rows));
    }

    /** 在一个事务里执行；抛异常就回滚。唯一约束冲突变成 {@link ConcurrencyConflictException}。 */
    public <T> T inTransaction(Supplier<T> work) {
        return translateErrors(() -> transactions.execute(status -> work.get()));
    }

    private static <T> T translateErrors(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException e) {
            if (isIntegrityViolation(e)) {
                throw new ConcurrencyConflictException("concurrent modification detected: " + e.getMostSpecificCause().getMessage());
            }
            throw e;
        }
    }

    /**
     * 是否违反唯一约束。大多数驱动 Spring 能识别成 {@link DataIntegrityViolationException}；
     * SQLite 驱动不填 SQLState，Spring 认不出来，这里再看一眼原始异常。
     */
    static boolean isIntegrityViolation(DataAccessException e) {
        if (e instanceof DataIntegrityViolationException) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getMessage() != null && sql.getMessage().contains("SQLITE_CONSTRAINT")) {
                return true;
            }
        }
        return false;
    }
}
