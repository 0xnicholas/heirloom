package com.heirloom.duckdb;

import com.heirloom.metadata.repository.ColumnProfileJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class FreshnessChecker {

    private final DuckDbRawStore duckDb;
    private final ColumnProfileJpaRepository profileRepo;
    private final int ttlMinutes;

    public FreshnessChecker(
        DuckDbRawStore duckDb,
        ColumnProfileJpaRepository profileRepo,
        @Value("${heirloom.duckdb.freshness.ttl-minutes:5}") int ttlMinutes
    ) {
        this.duckDb = duckDb;
        this.profileRepo = profileRepo;
        this.ttlMinutes = ttlMinutes;
    }

    public boolean isFresh(String tableFQN) {
        String duckDbName = DuckDbNaming.toDuckDbName(tableFQN);
        if (!duckDb.tableExists(duckDbName)) return false;

        var latest = profileRepo
            .findByTableFQNAndColumnNameOrderByProfiledAtDesc(tableFQN, "_row_count")
            .stream().findFirst();
        if (latest.isEmpty()) return false;

        return latest.get().getProfiledAt()
            .isAfter(Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES));
    }

    public Instant lastSyncedAt(String tableFQN) {
        return profileRepo
            .findByTableFQNAndColumnNameOrderByProfiledAtDesc(tableFQN, "_row_count")
            .stream().findFirst()
            .map(p -> p.getProfiledAt())
            .orElse(null);
    }

    public String ttlDescription() { return ttlMinutes + " minutes"; }

    public int getTtlMinutes() { return ttlMinutes; }
}
