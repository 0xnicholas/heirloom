package com.heirloom.core.metadata;

import java.util.List;

public record ColumnDef(
    String name,
    String dataType,
    Integer dataLength,
    Integer numericPrecision,
    Integer numericScale,
    boolean nullable,
    String defaultValue,
    String comment,
    Integer ordinalPosition,
    List<String> tags
) {}
