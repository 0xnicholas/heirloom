package com.heirloom.core.profiling;

import java.util.List;

public record ColumnProfileResult(
    String columnName,
    String dataType,
    long nullCount,
    double nullRate,
    long distinctCount,
    double distinctRate,
    long emptyStringCount,
    String minValue,
    String maxValue,
    Double avgLength,
    List<ValueFrequency> topValues,
    DataClass detectedClass,
    double qualityScore
) {}
