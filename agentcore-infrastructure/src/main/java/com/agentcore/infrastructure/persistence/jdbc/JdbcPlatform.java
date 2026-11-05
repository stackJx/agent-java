package com.agentcore.infrastructure.persistence.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * 数据库平台。DML 全部使用标准 SQL（分页用 JDBC 的 setMaxRows），只有建表脚本因平台而异。
 * 兼容库按语法归入对应平台：达梦、OceanBase(Oracle 模式) → ORACLE；金仓、openGauss、GaussDB → POSTGRESQL；
 * MariaDB、OceanBase(MySQL 模式)、TiDB → MYSQL。
 */
public enum JdbcPlatform {
    H2("h2"),
    MYSQL("mysql"),
    POSTGRESQL("postgresql"),
    ORACLE("oracle"),
    SQLSERVER("sqlserver"),
    SQLITE("sqlite");

    private final String scriptName;

    JdbcPlatform(String scriptName) {
        this.scriptName = scriptName;
    }

    /** classpath 上的建表脚本位置。 */
    public String schemaResource() {
        return "/com/agentcore/infrastructure/persistence/jdbc/schema-" + scriptName + ".sql";
    }

    /** 按名称解析（大小写不敏感，支持常见别名）；null / 空 / auto 返回 null 表示自动识别。 */
    public static JdbcPlatform parse(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("auto")) {
            return null;
        }
        JdbcPlatform p = fromProductName(name);
        if (p == null) {
            throw new IllegalArgumentException("unsupported JDBC platform: " + name
                    + " (supported: h2, mysql, postgresql, oracle, sqlserver, sqlite and compatible databases)");
        }
        return p;
    }

    /** 根据 JDBC 元数据中的数据库产品名识别。 */
    public static JdbcPlatform detect(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            JdbcPlatform p = fromProductName(product);
            if (p == null) {
                throw new IllegalStateException("cannot detect JDBC platform for database '" + product
                        + "'; set the platform explicitly (h2 / mysql / postgresql / oracle / sqlserver / sqlite)");
            }
            return p;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot connect to database to detect platform: " + e.getMessage(), e);
        }
    }

    static JdbcPlatform fromProductName(String product) {
        String p = product.toLowerCase(Locale.ROOT);
        if (p.contains("h2")) {
            return H2;
        }
        if (p.contains("sqlite")) {
            return SQLITE;
        }
        if (p.contains("mysql") || p.contains("mariadb") || p.contains("tidb") || p.equals("oceanbase")) {
            return MYSQL;
        }
        if (p.contains("postgres") || p.contains("kingbase") || p.contains("opengauss") || p.contains("gauss")
                || p.contains("cockroach")) {
            return POSTGRESQL;
        }
        if (p.contains("oracle") || p.contains("dm dbms") || p.equals("dm") || p.contains("dameng")) {
            return ORACLE;
        }
        if (p.contains("sql server") || p.contains("sqlserver") || p.contains("mssql")) {
            return SQLSERVER;
        }
        return null;
    }
}
