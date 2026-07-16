package com.heirloom.core.query;

import java.util.List;
import java.util.Map;

/**
 * Structured query object — parsed from existing v1 JSON DSL.
 * All field names validated against ResourceType definitions.
 */
public class SemanticQuery {

    private final String type;
    private final List<String> fields;
    private final Map<String, Object> filter;
    private final List<Map<String, Object>> traverse;
    private final Map<String, Object> aggregate;
    private final String sortField;
    private final String sortDirection;
    private final int limit;
    private final int offset;

    public SemanticQuery(String type, List<String> fields, Map<String, Object> filter,
                         List<Map<String, Object>> traverse, Map<String, Object> aggregate,
                         String sortField, String sortDirection, int limit, int offset) {
        this.type = type;
        this.fields = fields != null ? fields : List.of();
        this.filter = filter;
        this.traverse = traverse;
        this.aggregate = aggregate;
        this.sortField = sortField;
        this.sortDirection = sortDirection != null ? sortDirection : "asc";
        this.limit = limit > 0 ? limit : 20;
        this.offset = Math.max(0, offset);
    }

    public String getType() { return type; }
    public List<String> getFields() { return fields; }
    public Map<String, Object> getFilter() { return filter; }
    public List<Map<String, Object>> getTraverse() { return traverse; }
    public Map<String, Object> getAggregate() { return aggregate; }
    public String getSortField() { return sortField; }
    public String getSortDirection() { return sortDirection; }
    public int getLimit() { return limit; }
    public int getOffset() { return offset; }
}
