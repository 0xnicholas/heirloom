package com.heirloom.cdc.service;

import org.postgresql.replication.LogSequenceNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Decodes pgoutput binary protocol messages into CdcEvent objects.
 * <p>
 * pgoutput message types:
 * <ul>
 *   <li>'B' — BEGIN (transaction start)</li>
 *   <li>'C' — COMMIT (transaction end)</li>
 *   <li>'R' — RELATION (table schema)</li>
 *   <li>'I' — INSERT</li>
 *   <li>'U' — UPDATE</li>
 *   <li>'D' — DELETE</li>
 *   <li>'T' — TRUNCATE (ignored in v1)</li>
 * </ul>
 */
public class CdcPgOutputDecoder {

    private static final Logger log = LoggerFactory.getLogger(CdcPgOutputDecoder.class);

    // Relation ID → column names/types for decoding tuple data
    private final Map<Integer, RelationInfo> relations = new HashMap<>();

    /**
     * Decode a single pgoutput message from a ByteBuffer.
     * Returns null for BEGIN/COMMIT messages (handled internally).
     */
    public CdcEvent decode(ByteBuffer buffer) {
        byte type = buffer.get();

        return switch (type) {
            case 'B' -> { handleBegin(buffer); yield null; }
            case 'C' -> { handleCommit(buffer); yield null; }
            case 'R' -> { handleRelation(buffer); yield null; }
            case 'I' -> handleInsert(buffer);
            case 'U' -> handleUpdate(buffer);
            case 'D' -> handleDelete(buffer);
            case 'T' -> { /* truncate — ignore in v1 */ yield null; }
            default -> {
                log.warn("Unknown pgoutput message type: {}", (char) type);
                yield null;
            }
        };
    }

    // --- Message handlers ---

    private void handleBegin(ByteBuffer buffer) {
        // LSN (8 bytes) + commit timestamp (8 bytes) + xid (4 bytes)
        buffer.position(buffer.position() + 20);
    }

    private void handleCommit(ByteBuffer buffer) {
        // flags (1) + commit LSN (8) + transaction end LSN (8) + timestamp (8)
        buffer.position(buffer.position() + 25);
    }

    private void handleRelation(ByteBuffer buffer) {
        int relationId = buffer.getInt();
        String namespace = readCString(buffer);
        String tableName = readCString(buffer);
        byte replicaIdentity = buffer.get(); // not used in v1
        short columnCount = buffer.getShort();

        List<String> columnNames = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            byte flags = buffer.get();
            String colName = readCString(buffer);
            int typeOid = buffer.getInt();
            int typeMod = buffer.getInt();
            columnNames.add(colName);
        }

        relations.put(relationId, new RelationInfo(namespace, tableName, columnNames));
        log.debug("Relation registered: id={} {}.{} columns={}",
                relationId, namespace, tableName, columnNames);
    }

    private CdcEvent handleInsert(ByteBuffer buffer) {
        int relationId = buffer.getInt();
        byte tupleType = buffer.get(); // 'N' for new tuple
        RelationInfo rel = relations.get(relationId);
        if (rel == null) return null;

        Map<String, String> values = readTupleData(buffer, rel.columnNames());
        return new CdcEvent(rel.tableName(), "INSERT", values, Map.of(),
                readLsn(buffer), readTimestamp(buffer));
    }

    private CdcEvent handleUpdate(ByteBuffer buffer) {
        int relationId = buffer.getInt();
        RelationInfo rel = relations.get(relationId);
        if (rel == null) return null;

        // 'K' or 'O' for old tuple, 'N' for new tuple
        byte oldTupleType = buffer.get();
        Map<String, String> oldValues = Map.of();
        if (oldTupleType == 'K' || oldTupleType == 'O') {
            oldValues = readTupleData(buffer, rel.columnNames());
        }

        byte newTupleType = buffer.get();
        Map<String, String> newValues = Map.of();
        if (newTupleType == 'N') {
            newValues = readTupleData(buffer, rel.columnNames());
        }

        return new CdcEvent(rel.tableName(), "UPDATE", newValues, oldValues,
                readLsn(buffer), readTimestamp(buffer));
    }

    private CdcEvent handleDelete(ByteBuffer buffer) {
        int relationId = buffer.getInt();
        RelationInfo rel = relations.get(relationId);
        if (rel == null) return null;

        // 'K' or 'O' for old tuple
        byte oldTupleType = buffer.get();
        Map<String, String> oldValues = Map.of();
        if (oldTupleType == 'K' || oldTupleType == 'O') {
            oldValues = readTupleData(buffer, rel.columnNames());
        }

        return new CdcEvent(rel.tableName(), "DELETE", Map.of(), oldValues,
                readLsn(buffer), readTimestamp(buffer));
    }

    // --- Tuple data parsing ---

    private Map<String, String> readTupleData(ByteBuffer buffer, List<String> columnNames) {
        short columnCount = buffer.getShort();
        Map<String, String> values = new LinkedHashMap<>();

        for (int i = 0; i < columnCount; i++) {
            byte colType = buffer.get(); // 't' = text, 'n' = null, 'u' = unchanged toast
            String colName = i < columnNames.size() ? columnNames.get(i) : "col_" + i;

            if (colType == 'n') {
                continue; // null value — skip
            } else if (colType == 'u') {
                continue; // unchanged toast — skip in v1
            } else if (colType == 't') {
                int length = buffer.getInt();
                byte[] data = new byte[length];
                buffer.get(data);
                values.put(colName, new String(data, StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    // --- Helpers ---

    private String readCString(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        byte b;
        while ((b = buffer.get()) != 0) {
            sb.append((char) b);
        }
        return sb.toString();
    }

    private LogSequenceNumber readLsn(ByteBuffer buffer) {
        // LSN is embedded in the pgoutput copy message — we track it via
        // the stream API (setAppliedLSN/setFlushedLSN), not from message content.
        // Return a placeholder; actual LSN is managed by PGReplicationStream.
        return LogSequenceNumber.INVALID_LSN;
    }

    private long readTimestamp(ByteBuffer buffer) {
        return 0; // timestamp extraction not needed for v1
    }

    // --- Inner types ---

    record RelationInfo(String namespace, String tableName, List<String> columnNames) {}
}
