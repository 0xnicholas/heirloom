package com.heirloom.core.query;
import java.util.List;
import java.util.Map;
public record QueryResult(
    List<Map<String, Object>> rows,
    long totalCount,
    QueryMode resolvedMode,
    boolean fresh,
    Long executionTimeMs,
    Map<String, Object> meta,
    Map<String, Object> bindings
) {}
