package com.agentcore.infrastructure.persistence.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 基于 DriverManager 的最简数据源（独立部署 / 命令行用；每次新建连接，不带连接池）。
 * 在 Spring 等容器里请使用容器提供的连接池数据源。
 */
public final class SimpleDataSource implements DataSource {

    private final String url;
    private final String username;
    private final String password;

    public SimpleDataSource(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return username == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, username, password);
    }

    @Override
    public Connection getConnection(String user, String pass) throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    @Override
    public PrintWriter getLogWriter() {
        return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        DriverManager.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) {
        DriverManager.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() {
        return DriverManager.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public String toString() {
        return "SimpleDataSource[" + url + "]";
    }
}
