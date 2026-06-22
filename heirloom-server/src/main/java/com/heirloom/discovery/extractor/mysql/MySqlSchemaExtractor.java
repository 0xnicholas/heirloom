package com.heirloom.discovery.extractor.mysql;

import com.heirloom.discovery.extractor.DiscoveryConfig;
import com.heirloom.discovery.extractor.SchemaExtractor;
import com.heirloom.discovery.model.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class MySqlSchemaExtractor implements SchemaExtractor {
    private static final Logger log = LoggerFactory.getLogger(MySqlSchemaExtractor.class);
    private DataSource dataSource;

    @Override
    public void prepare(DiscoveryConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s", config.host(), config.port(), config.database()));
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(5);
        this.dataSource = new HikariDataSource(hc);
    }

    @Override public boolean testConnection() {
        try (Connection c = dataSource.getConnection()) { return c.isValid(5); }
        catch (SQLException e) { return false; }
    }

    @Override
    public RawSchema extract(DiscoveryConfig config) {
        List<RawTable> tables = new ArrayList<>();
        String schema = config.database(); // MySQL uses database as schema
        try (Connection conn = dataSource.getConnection()) {
            List<String> tableNames = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE'")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) tableNames.add(rs.getString(1)); }
            }
            for (String tn : tableNames) {
                List<RawColumn> cols = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position")) {
                    ps.setString(1, schema); ps.setString(2, tn);
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next())
                        cols.add(new RawColumn(rs.getString(1), rs.getString(2), "YES".equals(rs.getString(3)), null, rs.getString(4))); }
                }
                tables.add(new RawTable(schema, tn, null, cols, List.of(), null));
            }
        } catch (SQLException e) { log.error("MySQL extract failed", e); }
        String hash = computeHash(tables);
        return new RawSchema(config.host() + ":" + config.database(), "mysql", tables, List.of(), hash);
    }

    private String computeHash(List<RawTable> tables) {
        return Integer.toHexString(tables.stream().map(t -> t.tableName() + t.columns().stream().map(RawColumn::columnName).collect(Collectors.joining())).collect(Collectors.joining()).hashCode());
    }

    @Override public Set<ExtractorCapability> capabilities() { return Set.of(ExtractorCapability.SCHEMA_METADATA); }
}
