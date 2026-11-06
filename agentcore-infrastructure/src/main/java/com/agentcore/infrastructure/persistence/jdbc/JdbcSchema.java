package com.agentcore.infrastructure.persistence.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;

/**
 * 建表：读取对应平台的脚本，只创建缺失的表（不修改、不删除已有表）。
 * 生产环境由 DBA 管理表结构时，可以不调用它，直接拿脚本去执行（脚本在 jar 的
 * {@code com/agentcore/infrastructure/persistence/jdbc/schema-*.sql}，把 {@code ${prefix}} 换成表名前缀）。
 */
public final class JdbcSchema {

    public static final List<String> TABLES = List.of("conversation", "conversation_event", "memory");

    private JdbcSchema() {
    }

    /** 返回本次新建的表名。 */
    public static List<String> createIfMissing(JdbcStore store) {
        Map<String, List<String>> sections = sections(script(store));
        List<String> created = new ArrayList<>();
        for (String table : TABLES) {
            if (exists(store, table)) {
                continue;
            }
            List<String> statements = sections.getOrDefault(table, List.of());
            store.inTransaction(() -> {
                statements.forEach(store::execute);
                return null;
            });
            created.add(store.table(table));
        }
        return created;
    }

    /** 渲染后的建表脚本（便于导出给 DBA）。 */
    public static String script(JdbcStore store) {
        try (InputStream in = JdbcSchema.class.getResourceAsStream(store.platform().schemaResource())) {
            if (in == null) {
                throw new IllegalStateException("schema script not found: " + store.platform().schemaResource());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${prefix}", store.tablePrefix());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 用一条不返回数据的查询探测表是否存在（比 DatabaseMetaData 在各库间更一致）。 */
    static boolean exists(JdbcStore store, String table) {
        try {
            store.jdbc().sql("SELECT 1 FROM " + store.table(table) + " WHERE 1 = 0").query().listOfRows();
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    /** 按 "-- table: xxx" 注释把脚本切成每张表的语句列表。 */
    static Map<String, List<String>> sections(String script) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = null;
        StringBuilder statement = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("-- table:")) {
                current = trimmed.substring("-- table:".length()).strip();
                sections.put(current, new ArrayList<>());
                continue;
            }
            if (trimmed.startsWith("--") || trimmed.isEmpty() || current == null) {
                continue;
            }
            statement.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = statement.toString().strip();
                sections.get(current).add(sql.substring(0, sql.length() - 1).strip());
                statement.setLength(0);
            }
        }
        return sections;
    }
}
