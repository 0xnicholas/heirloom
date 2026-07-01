package com.heirloom.cdc.service;

import org.postgresql.replication.LogSequenceNumber;

import java.util.Map;

/**
 * A decoded pgoutput change event.
 */
public record CdcEvent(
        String tableName,
        String operation,  // INSERT, UPDATE, DELETE
        Map<String, String> newValues,
        Map<String, String> oldValues,
        LogSequenceNumber lsn,
        long timestamp
) {}
