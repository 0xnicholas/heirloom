package com.heirloom.core.query;
public record QueryRequest(
    QueryMode mode,
    QueryPayload payload,
    String rawTable,
    String rawSql,
    ResourceRef resource,
    DrillDown drillDown
) {}
