package com.heirloom.duckdb;

public record SyncResult(String tableFQN, long rowCount, long durationMs) {}