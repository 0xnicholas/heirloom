package com.heirloom.core.query;
import java.util.List;
public record RouteDecision(
    QueryMode mode,
    QueryPayload payload,
    String rawTable,
    String rawSql,
    ResourceRef resource,
    DrillDown drillDown,
    List<RouteStep> steps
) {}
