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
        } catch (SQLException e) {
            log.error("Connection test failed", e);
            return false;
        }
    }

    @Override
    public RawSchema extract(DiscoveryConfig config) {
        List<RawTable> tables = new ArrayList<>();
        List<RawRelationship> relationships = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            String schema = config.schema() != null ? config.schema() : "public";

            // Get table names
            List<String> tableNames = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_type = 'BASE TABLE'")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) tableNames.add(rs.getString(1));
                }
            }

            for (String tableName : tableNames) {
                // Get columns
                List<RawColumn> columns = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_name, data_type, is_nullable, column_default, " +
                    "pg_catalog.col_description(c.oid, ordinal_position) AS comment " +
                    "FROM information_schema.columns c " +
                    "JOIN pg_class cls ON cls.relname = ? " +
                    "JOIN pg_namespace ns ON ns.oid = cls.relnamespace AND ns.nspname = ? " +
                    "WHERE c.table_schema = ? AND c.table_name = ? " +
                    "ORDER BY c.ordinal_position")) {
                    ps.setString(1, tableName);
                    ps.setString(2, schema);
                    ps.setString(3, schema);
                    ps.setString(4, tableName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            columns.add(new RawColumn(
                                rs.getString("column_name"),
                                rs.getString("data_type"),
                                "YES".equals(rs.getString("is_nullable")),
                                rs.getString("comment"),
                                rs.getString("column_default")));
                        }
                    }
                }

                // Get constraints
                List<RawConstraint> constraints = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tc.constraint_type, kcu.column_name, " +
                    "ccu.table_schema AS target_schema, ccu.table_name AS target_table, " +
                    "ccu.column_name AS target_column, rc.delete_rule, cc.check_clause " +
                    "FROM information_schema.table_constraints tc " +
                    "LEFT JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
                    "LEFT JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name " +
                    "LEFT JOIN information_schema.referential_constraints rc ON tc.constraint_name = rc.constraint_name " +
                    "LEFT JOIN information_schema.check_constraints cc ON tc.constraint_name = cc.constraint_name " +
                    "WHERE tc.table_schema = ? AND tc.table_name = ?")) {
                    ps.setString(1, schema);
                    ps.setString(2, tableName);

                    Map<String, List<String>> pkColumns = new LinkedHashMap<>();
                    Map<String, RawConstraint> constraintMap = new LinkedHashMap<>();

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String type = rs.getString("constraint_type");
                            String col = rs.getString("column_name");
                            if (col == null) continue;

                            if ("PRIMARY KEY".equals(type)) {
                                pkColumns.computeIfAbsent("PK", k -> new ArrayList<>()).add(col);
                            } else if ("FOREIGN KEY".equals(type)) {
                                String key = "FK_" + rs.getString("target_table");
                                try {
                                    String targetTable = rs.getString("target_table");
                                    String targetCol = rs.getString("target_column");
                                    String deleteRule = rs.getString("delete_rule");
                                    constraintMap.computeIfAbsent(key, k -> new RawConstraint(
                                        RawConstraint.ConstraintType.FOREIGN_KEY,
                                        new ArrayList<>(), targetTable,
                                        List.of(targetCol != null ? targetCol : ""),
                                        deleteRule, null)).columns().add(col);
                                } catch (SQLException se) {
                                    log.warn("Failed to read FK constraint", se);
                                }
                            } else if ("UNIQUE".equals(type)) {
                                pkColumns.computeIfAbsent("UQ_" + col, k -> new ArrayList<>()).add(col);
                            }
                        }
                    }

                    pkColumns.forEach((k, v) -> constraints.add(
                        new RawConstraint(k.equals("PK") ? RawConstraint.ConstraintType.PRIMARY_KEY : RawConstraint.ConstraintType.UNIQUE,
                            v, null, List.of(), null, null)));
                    constraints.addAll(constraintMap.values());
                }

                // Get table comment
                String comment = null;
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT obj_description(cls.oid) FROM pg_class cls " +
                    "JOIN pg_namespace ns ON ns.oid = cls.relnamespace " +
                    "WHERE cls.relname = ? AND ns.nspname = ?")) {
                    ps.setString(1, tableName);
                    ps.setString(2, schema);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) comment = rs.getString(1);
                    }
                }

                tables.add(new RawTable(schema, tableName, comment, columns, constraints, null));
            }

        } catch (SQLException e) {
            log.error("Failed to extract schema", e);
            throw new RuntimeException("Schema extraction failed: " + e.getMessage(), e);
        }

        String contentHash = computeHash(tables);
        return new RawSchema(config.host() + ":" + config.database(), config.sourceType(), tables, relationships, contentHash);
    }

    private String computeHash(List<RawTable> tables) {
        StringBuilder sb = new StringBuilder();
        for (RawTable t : tables) {
            sb.append(t.tableName());
            for (RawColumn c : t.columns()) sb.append(c.columnName()).append(c.rawType());
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    @Override
    public Set<ExtractorCapability> capabilities() {
        return Set.of(ExtractorCapability.SCHEMA_METADATA, ExtractorCapability.CONSTRAINTS);
    }
}
