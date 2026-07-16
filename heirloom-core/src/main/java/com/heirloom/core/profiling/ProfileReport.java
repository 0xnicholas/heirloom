package com.heirloom.core.profiling;

import java.time.Instant;
import java.util.List;

public record ProfileReport(
    String tableFQN,
    long rowCount,
    long columnCount,
    Instant profiledAt,
    long durationMs,
    List<ColumnProfileResult> columns,
    double overallQualityScore
) {}
