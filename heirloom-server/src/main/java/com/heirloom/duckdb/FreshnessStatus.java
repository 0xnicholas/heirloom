package com.heirloom.duckdb;
import java.time.Instant;
public record FreshnessStatus(
    String tableFQN,
    boolean fresh,
    Instant lastSyncedAt,
    String ttl
) {}
