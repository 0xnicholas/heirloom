package com.heirloom.profiling.service;

import com.heirloom.core.profiling.SamplingStrategy;

public class PostgresSamplingStrategy implements SamplingStrategy {
    private static final long SAMPLE_THRESHOLD = 1_000_000L;
    private final double sampleRate;

    public PostgresSamplingStrategy() { this(0.1); }
    public PostgresSamplingStrategy(double sampleRate) { this.sampleRate = sampleRate; }

    @Override
    public String apply(String tableSql, long estimatedRows) {
        if (estimatedRows > SAMPLE_THRESHOLD) {
            return String.format("(SELECT * FROM %s TABLESAMPLE BERNOULLI(%.0f)) AS _sample",
                tableSql, sampleRate * 100);
        }
        return tableSql;
    }
}
