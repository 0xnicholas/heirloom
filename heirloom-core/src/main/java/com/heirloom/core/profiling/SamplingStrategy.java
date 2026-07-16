package com.heirloom.core.profiling;

public interface SamplingStrategy {
    String apply(String tableSql, long estimatedRows);
}
