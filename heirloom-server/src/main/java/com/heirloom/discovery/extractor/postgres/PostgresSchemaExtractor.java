package com.heirloom.discovery.extractor.postgres;

import com.heirloom.discovery.extractor.DiscoveryConfig;
import com.heirloom.discovery.extractor.SchemaExtractor;
import com.heirloom.discovery.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class PostgresSchemaExtractor implements SchemaExtractor {
    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaExtractor.class);
    private DataSource dataSource;
    private DiscoveryConfig config;

    @Override
    public void prepare(DiscoveryConfig config) {
        this.config = config;
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", config.host(), config.port(), config.database()));
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(5);
        this.dataSource = new HikariDataSource(hc);
    }

    @Override
    public boolean testConnection() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) { return false; }
    }

    @Override
    public RawSchema extract(DiscoveryConfig config) {
        List<RawTable> tables = new ArrayList<>();
        String schema = config.schema() != null ? config.schema() : "public";

        try (Connection conn = dataSource.getConnection()) {
            List<String> tableNames = getTableNames(conn, schema);
            for (String tableName : tableNames) {
                tables.add(extractTable(conn, schema, tableName));
            }
        } catch (SQLException e) {
            log.error("Failed to extract schema", e);
            throw new RuntimeException(e);
        }

        return new RawSchema(config.host() + ":" + config.database(), config.sourceType(),
            tables, List.of(), computeHash(tables));
    }

    private List<String> getTableNames(Connection conn, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE'")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) names.add(rs.getString(1)); }
        }
        return names;
    }

    private RawTable extractTable(Connection conn, String schema, String tableName) throws SQLException {
        List<RawColumn> columns = getColumns(conn, schema, tableName);
        List<RawConstraint> constraints = getConstraints(conn, schema, tableName);
        String comment = getTableComment(conn, schema, tableName);
        return new RawTable(schema, tableName, comment, columns, constraints, null);
    }

    private List<RawColumn> getColumns(Connection conn, String schema, String tableName) throws SQLException {
        List<RawColumn> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position")) {
            ps.setString(1, schema); ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(new RawColumn(
                    rs.getString("column_name"), rs.getString("data_type"),
                    "YES".equals(rs.getString("is_nullable")), null, rs.getString("column_default")));
            }
        }
        return cols;
    }

    private List<RawConstraint> getConstraints(Connection conn, String schema, String tableName) throws SQLException {
        List<RawConstraint> constraints = new ArrayList<>();
        // Primary keys
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT kcu.column_name FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu ON tc.constraint_name=kcu.constraint_name " +
            "WHERE tc.constraint_type='PRIMARY KEY' AND tc.table_schema=? AND tc.table_name=? " +
            "ORDER BY kcu.ordinal_position")) {
            ps.setString(1, schema); ps.setString(2, tableName);
            List<String> pkCols = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) pkCols.add(rs.getString(1)); }
            if (!pkCols.isEmpty()) constraints.add(new RawConstraint(
                RawConstraint.ConstraintType.PRIMARY_KEY, pkCols, null, List.of(), null, null));
        }
        // Foreign keys
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT kcu.column_name, ccu.table_name AS target_table, ccu.column_name AS target_column, rc.delete_rule " +
            "FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu ON tc.constraint_name=kcu.constraint_name " +
            "JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name=ccu.constraint_name " +
            "LEFT JOIN information_schema.referential_constraints rc ON tc.constraint_name=rc.constraint_name " +
            "WHERE tc.constraint_type='FOREIGN KEY' AND tc.table_schema=? AND tc.table_name=?")) {
            ps.setString(1, schema); ps.setString(2, tableName);
            Map<String, RawConstraint> fkMap = new LinkedHashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("column_name");
                    String target = rs.getString("target_table");
                    String targetCol = rs.getString("target_column");
                    String deleteRule = rs.getString("delete_rule");
                    String key = "FK_" + target;
                    fkMap.computeIfAbsent(key, k -> new RawConstraint(
                        RawConstraint.ConstraintType.FOREIGN_KEY, new ArrayList<>(),
                        target, List.of(targetCol),
                        deleteRule, null)).columns().add(colName);
                }
            }
            constraints.addAll(fkMap.values());
        }
        return constraints;
    }

    private String getTableComment(Connection conn, String schema, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT obj_description(c.oid) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relname=? AND n.nspname=?")) {
            ps.setString(1, tableName); ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString(1); }
        }
        return null;
    }

    private String computeHash(List<RawTable> tables) {
        return Integer.toHexString(tables.stream()
            .map(t -> t.tableName() + t.columns().stream().map(RawColumn::columnName).collect(Collectors.joining()))
            .collect(Collectors.joining()).hashCode());
    }

    @Override
    public Set<ExtractorCapability> capabilities() {
        return Set.of(ExtractorCapability.SCHEMA_METADATA, ExtractorCapability.CONSTRAINTS);
    }

    public void close() {
        if (dataSource instanceof HikariDataSource hds) hds.close();
    }
}
