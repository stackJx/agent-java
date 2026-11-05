/**
 * 数据库存储的自动装配。{@code agentcore.persistence.jdbc.enabled=true} 且应用里只有一个 {@code DataSource} 时生效，
 * 使用应用已有的数据源和连接池；会话与长期记忆改存数据库，未开启时继续用文件。
 */
package com.agentcore.spring.jdbc;
