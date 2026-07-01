package com.heirloom.cdc.service;

import com.heirloom.cdc.domain.CdcOffset;
import com.heirloom.cdc.domain.CdcSource;
import com.heirloom.cdc.repository.CdcOffsetRepository;
import org.postgresql.PGConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Manages PostgreSQL logical replication connections and event streaming.
 * <p>
 * Uses the pgoutput plugin via JDBC replication protocol. Each CdcSource
 * gets one replication slot, one publication, and one streaming connection.
 */
public class CdcEngine implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(CdcEngine.class);

    private final CdcSource source;
    private final CdcOffsetRepository offsetRepo;
    private final CdcEventMapper eventMapper;
    private volatile boolean running = true;
    private String lastLsn;
    private long eventsProcessed;

    public CdcEngine(CdcSource source, CdcOffsetRepository offsetRepo, CdcEventMapper eventMapper) {
        this.source = source;
        this.offsetRepo = offsetRepo;
        this.eventMapper = eventMapper;
    }

    public void stop() {
        running = false;
    }

    public String getLastLsn() { return lastLsn; }
    public long getEventsProcessed() { return eventsProcessed; }

    @Override
    public void run() {
        try {
            setupPublicationAndSlot();
            streamChanges();
        } catch (Exception e) {
            log.error("CDC engine error for source '{}': {}", source.getName(), e.getMessage(), e);
        }
    }

    // --- Setup ---

    private void setupPublicationAndSlot() throws SQLException {
        try (Connection conn = createConnection();
             Statement stmt = conn.createStatement()) {

            // Check wal_level
            var rs = stmt.executeQuery("SHOW wal_level");
            rs.next();
            String walLevel = rs.getString(1);
            if (!"logical".equals(walLevel)) {
                throw new IllegalStateException(
                        "wal_level must be 'logical' for CDC. Current: " + walLevel);
            }

            // Create publication if not exists
            String tableList = buildTableList();
            stmt.execute("CREATE PUBLICATION " + source.getPublicationName()
                    + " FOR TABLE " + tableList);

            log.info("Publication '{}' created for tables: {}",
                    source.getPublicationName(), tableList);
        }

        // Create replication slot (requires replication connection)
        try (Connection replConn = createReplicationConnection();
             Statement stmt = replConn.createStatement()) {

            stmt.execute("CREATE_REPLICATION_SLOT " + source.getSlotName()
                    + " LOGICAL pgoutput");
            log.info("Replication slot '{}' created", source.getSlotName());
        }
    }

    private String buildTableList() {
        StringBuilder sb = new StringBuilder();
        for (String tableName : source.getWatchedTables().keySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(source.getPgSchema()).append(".").append(tableName);
        }
        return sb.toString();
    }

    // --- Streaming ---

    private void streamChanges() throws Exception {
        try (Connection conn = createReplicationConnection()) {
            PGConnection pgConn = conn.unwrap(PGConnection.class);

            String startLsnStr = offsetRepo.findBySourceName(source.getName())
                    .map(o -> o.getLsn())
                    .orElse(null);

            PGReplicationStream stream = pgConn.getReplicationAPI()
                    .replicationStream()
                    .logical()
                    .withSlotName(source.getSlotName())
                    .withStartPosition(startLsnStr != null
                            ? LogSequenceNumber.valueOf(startLsnStr)
                            : LogSequenceNumber.INVALID_LSN)
                    .start();

            log.info("CDC streaming started for '{}' from LSN={}",
                    source.getName(), startLsnStr != null ? startLsnStr : "beginning");

            CdcPgOutputDecoder decoder = new CdcPgOutputDecoder();
            long lastStatusUpdate = System.currentTimeMillis();

            while (running) {
                ByteBuffer msg = stream.readPending();

                if (msg == null) {
                    Thread.sleep(10);
                    // Periodic status update to prevent slot bloat
                    if (System.currentTimeMillis() - lastStatusUpdate > 10_000) {
                        stream.forceUpdateStatus();
                        lastStatusUpdate = System.currentTimeMillis();
                    }
                    continue;
                }

                CdcEvent event = decoder.decode(msg);
                if (event != null && source.getWatchedTables().containsKey(event.tableName())) {
                    eventMapper.handleEvent(event, source);

                    // Advance LSN after successful processing
                    stream.setAppliedLSN(event.lsn());
                    stream.setFlushedLSN(event.lsn());

                    lastLsn = event.lsn().toString();
                    offsetRepo.save(new CdcOffset(source.getName(), lastLsn));
                    eventsProcessed++;
                }
            }

            stream.close();
            log.info("CDC streaming stopped for '{}'. Processed {} events, last LSN={}",
                    source.getName(), eventsProcessed, lastLsn);
        }
    }

    // --- Connections ---

    private Connection createConnection() throws SQLException {
        String url = "jdbc:postgresql://" + source.getPgHost() + ":" + source.getPgPort()
                + "/" + source.getPgDatabase();
        Properties props = new Properties();
        props.setProperty("user", source.getPgUsername());
        props.setProperty("password", source.getPgPassword());
        return DriverManager.getConnection(url, props);
    }

    private Connection createReplicationConnection() throws SQLException {
        String url = "jdbc:postgresql://" + source.getPgHost() + ":" + source.getPgPort()
                + "/" + source.getPgDatabase();
        Properties props = new Properties();
        props.setProperty("user", source.getPgUsername());
        props.setProperty("password", source.getPgPassword());
        props.setProperty("replication", "database");
        props.setProperty("assumeMinServerVersion", "14");
        return DriverManager.getConnection(url, props);
    }
}
