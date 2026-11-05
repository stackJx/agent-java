/**
 * 数据库存储（标准 JDBC，不依赖 ORM，目的是适配尽量多的数据库）。
 *
 * <ul>
 *   <li>{@link com.agentcore.infrastructure.persistence.jdbc.JdbcStore}：数据源 + 平台 + 表名前缀，提供事务和异常翻译；</li>
 *   <li>{@link com.agentcore.infrastructure.persistence.jdbc.JdbcPlatform}：从连接元数据自动识别数据库，选择对应的建表脚本；</li>
 *   <li>{@link com.agentcore.infrastructure.persistence.jdbc.JdbcSchema}：按需建表（只建缺少的表）；</li>
 *   <li>{@link com.agentcore.infrastructure.persistence.jdbc.JdbcConversationRepository}：事件表 + 摘要表，用 {@code last_seq} 做乐观并发控制；</li>
 *   <li>{@link com.agentcore.infrastructure.persistence.jdbc.JdbcMemoryRepository}：长期记忆，按 scope 隔离。</li>
 * </ul>
 */
package com.agentcore.infrastructure.persistence.jdbc;
