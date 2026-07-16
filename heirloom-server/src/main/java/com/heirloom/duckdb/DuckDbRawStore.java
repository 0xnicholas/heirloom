package com.heirloom.duckdb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DuckDbRawStore implements AutoCloseable {

    private final Connection conn;

    public DuckDbRawStore(@Value("${heirloom.duckdb.url:jdbc:duckdb:data/heirloom_raw.db}") String url) {
        try {
            this.conn = DriverManager.getConnection(url);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open DuckDB connection: " + url, e);
        }
    }

    public List<Map<String, Object>> query(String sql) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            var meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                results.add(row);
            }
        } catch (SQLException e) {
            throw new RuntimeException("DuckDB query failed: " + sql, e);
        }
        return results;
    }

    public synchronized void execute(String sql) {
        try (var stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("DuckDB execute failed: " + sql, e);
        }
    }

    public Connection getConnection() {
        return conn;
    }

    public boolean tableExists(String tableName) {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '" + tableName + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}