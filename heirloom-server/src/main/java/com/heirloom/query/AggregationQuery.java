package com.heirloom.query;

import java.util.List;
import java.util.Map;

/**
 * JSON DSL aggregation query structure.
 * Supports $count, $sum, $avg, $min, $max with groupBy.
 */
public record AggregationQuery(
    String type,
    Map<String, String> aggregate,  // e.g., {"$count": "*", "$sum": "total", "$avg": "total"}
    List<String> groupBy,           // e.g., ["tier", "status"]
    Map<String, Object> filter      // optional: {"field": "createdAt", "op": "$gte", "value": "2025-01-01"}
) {}
