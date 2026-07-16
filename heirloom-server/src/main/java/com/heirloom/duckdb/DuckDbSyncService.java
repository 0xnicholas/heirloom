package com.heirloom.duckdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.metadata.ColumnDef;
import com.heirloom.metadata.domain.ColumnProfileEntity;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.metadata.repository.ColumnProfileRepository;
import com.heirloom.repository.TableRepository;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DuckDbSyncService {

    private static final String DEFAULT_SCHEMA = "";

    private final DuckDbRawStore duckDb;
    private final DataSource sourceDataSource;
    private final TableRepository tableRepo;
    private final ColumnProfileRepository profileRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Object> tableLocks = new ConcurrentHashMap<>();

    public DuckDbSyncService(
            DuckDbRawStore duckDb,
            DataSource sourceDataSource,
            TableRepository tableRepo,
            ColumnProfileRepository profileRepo) {
        this.duckDb = duckDb;
        this.sourceDataSource = sourceDataSource;
        this.tableRepo = tableRepo;
        this.profileRepo = profileRepo;
    }

    public SyncResult sync(String tableFQN) {
        Object lock = tableLocks.computeIfAbsent(tableFQN, k -> new Object());
        synchronized (lock) {
            long start = System.currentTimeMillis();
            try {
                TableEntity table = tableRepo.findByFQN(tableFQN)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown table: " + tableFQN));

                List<ColumnDef> columns;
                try {
                    columns = objectMapper.readValue(
                            table.getColumnsJson(),
                            new TypeReference<List<ColumnDef>>() {});
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse columnsJson for " + tableFQN, e);
                }

                String duckDbName = DuckDbNaming.toDuckDbName(tableFQN);
                String tmpName = duckDbName + "_tmp";

                duckDb.execute("DROP TABLE IF EXISTS \"" + tmpName + "\"");
                duckDb.execute(buildCreateTableSql(columns, tmpName));

                long rowCount = appendFromPostgres(tableFQN, columns, tmpName);

                duckDb.execute("DROP TABLE IF EXISTS \"" + duckDbName + "\"");
                duckDb.execute("ALTER TABLE \"" + tmpName + "\" RENAME TO \"" + duckDbName + "\"");

                saveSyncTrace(tableFQN, rowCount);
                return new SyncResult(tableFQN, rowCount, System.currentTimeMillis() - start);
            } finally {
                tableLocks.remove(tableFQN);
            }
        }
    }

    private void saveSyncTrace(String tableFQN, long rowCount) {
        try {
            ColumnProfileEntity trace = new ColumnProfileEntity();
            trace.setTableFQN(tableFQN);
            trace.setColumnName("_row_count");
            trace.setProfiledAt(java.time.Instant.now());
            profileRepo.create(trace);
        } catch (Exception ignored) {
        }
    }

    private long appendFromPostgres(String tableFQN, List<ColumnDef> columns, String targetTable) {
        long count = 0;
        Connection src = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        DuckDBConnection duckConn = null;
        DuckDBAppender appender = null;
        try {
            src = sourceDataSource.getConnection();
            ps = src.prepareStatement("SELECT * FROM " + tableFQN);
            rs = ps.executeQuery();
            duckConn = (DuckDBConnection) duckDb.getConnection();
            appender = duckConn.createAppender(DEFAULT_SCHEMA, targetTable);
            int colCount = columns.size();
            while (rs.next()) {
                appender.beginRow();
                for (int i = 0; i < colCount; i++) {
                    ColumnDef col = columns.get(i);
                    Object v = rs.getObject(i + 1);
                    appendRowValue(appender, v, col);
                }
                appender.endRow();
                count++;
            }
        } catch (SQLException | RuntimeException e) {
            throw new RuntimeException("Failed to sync " + tableFQN + " from Postgres", e);
        } finally {
            closeQuietly(appender);
            closeQuietly(rs);
            closeQuietly(ps);
            closeQuietly(src);
        }
        return count;
    }

    private void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private void appendRowValue(DuckDBAppender appender, Object v, ColumnDef col) throws SQLException {
        if (v == null) {
            appender.append((String) null);
            return;
        }
        String type = col.dataType() == null ? "" : col.dataType().toLowerCase();
        switch (type) {
            case "integer", "int", "int4", "smallint", "int2", "bigint", "int8" -> {
                appender.append(((Number) v).longValue());
            }
            case "real", "float4", "double precision", "float8" -> {
                appender.append(((Number) v).doubleValue());
            }
            case "boolean", "bool" -> {
                appender.append((Boolean) v);
            }
            default -> appender.append(String.valueOf(v));
        }
    }

    private String buildCreateTableSql(List<ColumnDef> columns, String tableName) {
        StringBuilder sb = new StringBuilder("CREATE TABLE \"").append(tableName).append("\" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            ColumnDef c = columns.get(i);
            sb.append('"').append(c.name()).append("\" ").append(mapType(c.dataType()));
        }
        sb.append(")");
        return sb.toString();
    }

    private String mapType(String pgType) {
        if (pgType == null) return "VARCHAR";
        return switch (pgType.toLowerCase()) {
            case "integer", "int", "int4", "smallint", "int2", "bigint", "int8" -> "BIGINT";
            case "real", "float4", "double precision", "float8", "numeric", "decimal" -> "DOUBLE";
            case "boolean", "bool" -> "BOOLEAN";
            case "date" -> "DATE";
            case "timestamp", "timestamptz", "time" -> "TIMESTAMP";
            default -> "VARCHAR";
        };
    }
}