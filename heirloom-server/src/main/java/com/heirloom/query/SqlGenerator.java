package com.heirloom.query;

import com.heirloom.core.query.GeneratedSql;
import com.heirloom.core.query.QueryParseException;
import com.heirloom.core.query.SemanticQuery;
import com.heirloom.repository.MappingRuleRepository;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.Field;
import com.heirloom.schema.domain.ResourceType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Generates parameterized SQL from a SemanticQuery using PreparedStatement.
 * Column names are resolved from ResourceType.fields whitelist — never from
 * raw request strings. Table names come from MappingRule.
 */
@Component
public class SqlGenerator implements com.heirloom.core.query.SqlGenerator {

    private final TypeRepository typeRepo;
    private final MappingRuleRepository mappingRepo;

    public SqlGenerator(TypeRepository typeRepo, MappingRuleRepository mappingRepo) {
        this.typeRepo = typeRepo;
        this.mappingRepo = mappingRepo;
    }

    @SuppressWarnings("unchecked")
    @Override
    public GeneratedSql generate(SemanticQuery query) {
        ResourceType type = typeRepo.findByName(query.getType())
                .orElseThrow(() -> new QueryParseException("Type '" + query.getType() + "' not found"));

        Map<String, String> fieldToCol = resolveColumns(query.getFields(), type);
        String table = resolveTable(query.getType(), query.getFields(), fieldToCol);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        Map<String, Object> agg = query.getAggregate();
        if (agg != null && !agg.isEmpty()) {
            buildAggregateQuery(sql, params, agg, fieldToCol, query, table);
        } else {
            buildSelectQuery(sql, params, fieldToCol, query, table);
        }

        return new GeneratedSql(sql.toString(), params);
    }

    private void buildSelectQuery(StringBuilder sql, List<Object> params,
                                   Map<String, String> fieldToCol, SemanticQuery query,
                                   String table) {
        List<String> selectParts = new ArrayList<>();
        for (var entry : fieldToCol.entrySet()) {
            selectParts.add("t0." + entry.getValue() + " AS \"" + entry.getKey() + "\"");
        }
        sql.append("SELECT ").append(String.join(", ", selectParts));
        sql.append(" FROM ").append(table).append(" t0");

        appendWhere(sql, params, query.getFilter(), fieldToCol, "t0");
        appendOrderBy(sql, query, fieldToCol);
        appendLimit(sql, params, query);
    }

    @SuppressWarnings("unchecked")
    private void buildAggregateQuery(StringBuilder sql, List<Object> params,
                                      Map<String, Object> agg, Map<String, String> fieldToCol,
                                      SemanticQuery query, String table) {
        String func = (String) agg.get("function");
        String aggField = (String) agg.get("field");
        List<String> groupBy = (List<String>) agg.getOrDefault("groupBy", List.of());

        String col = fieldToCol.getOrDefault(aggField, aggField);
        sql.append("SELECT ").append(func.toUpperCase()).append("(t0.").append(col)
           .append(") AS \"").append(func).append("_").append(aggField).append("\"");

        List<String> gbCols = new ArrayList<>();
        for (String gb : groupBy) {
            String gbCol = fieldToCol.getOrDefault(gb, gb);
            gbCols.add("t0." + gbCol);
            sql.append(", t0.").append(gbCol).append(" AS \"").append(gb).append("\"");
        }

        sql.append(" FROM ").append(table).append(" t0");
        appendWhere(sql, params, query.getFilter(), fieldToCol, "t0");

        if (!gbCols.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", gbCols));
        }

        appendOrderBy(sql, query, fieldToCol);
        appendLimit(sql, params, query);
    }

    @SuppressWarnings("unchecked")
    private void appendWhere(StringBuilder sql, List<Object> params,
                              Map<String, Object> filter, Map<String, String> fieldToCol,
                              String alias) {
        if (filter == null || filter.isEmpty()) return;
        sql.append(" WHERE ");
        appendFilter(sql, params, filter, fieldToCol, alias);
    }

    @SuppressWarnings("unchecked")
    private void appendFilter(StringBuilder sql, List<Object> params,
                               Map<String, Object> filter, Map<String, String> fieldToCol,
                               String alias) {
        String field = (String) filter.get("field");
        String op = (String) filter.get("op");

        if (field != null && op != null) {
            String col = fieldToCol.getOrDefault(field, field);
            sql.append(alias).append(".").append(col).append(" ").append(mapOp(op)).append(" ?");
            params.add(filter.get("value"));
        } else {
            List<String> parts = new ArrayList<>();
            for (var entry : filter.entrySet()) {
                String key = entry.getKey();
                if ("$and".equals(key) || "$or".equals(key)) {
                    List<Map<String, Object>> subs = (List<Map<String, Object>>) entry.getValue();
                    List<String> subParts = new ArrayList<>();
                    for (Map<String, Object> sf : subs) {
                        StringBuilder sb = new StringBuilder();
                        appendFilter(sb, params, sf, fieldToCol, alias);
                        subParts.add(sb.toString());
                    }
                    String joiner = "$or".equals(key) ? " OR " : " AND ";
                    parts.add("(" + String.join(joiner, subParts) + ")");
                }
            }
            sql.append(String.join(" AND ", parts));
        }
    }

    private void appendOrderBy(StringBuilder sql, SemanticQuery query,
                                Map<String, String> fieldToCol) {
        if (query.getSortField() == null) return;
        String col = fieldToCol.getOrDefault(query.getSortField(), query.getSortField());
        sql.append(" ORDER BY t0.").append(col).append(" ")
           .append("desc".equalsIgnoreCase(query.getSortDirection()) ? "DESC" : "ASC");
    }

    private void appendLimit(StringBuilder sql, List<Object> params, SemanticQuery query) {
        sql.append(" LIMIT ?");
        params.add(query.getLimit());
        if (query.getOffset() > 0) {
            sql.append(" OFFSET ?");
            params.add(query.getOffset());
        }
    }

    private String resolveTable(String typeName, List<String> fields, Map<String, String> fieldToCol) {
        if (fields.isEmpty()) return typeName.toLowerCase();
        // Extract table from first field's mapping; fallback to type name
        for (var entry : fieldToCol.entrySet()) {
            String fqn = "default." + typeName;
            var rule = mappingRepo.findByTypeAndField(fqn, entry.getKey());
            if (rule.isPresent()) {
                String colFQN = rule.get().getColumnFQN();
                String[] parts = colFQN.split("\\.");
                if (parts.length >= 3) {
                    return parts[parts.length - 3] + "." + parts[parts.length - 2];
                }
            }
        }
        return typeName.toLowerCase();
    }

    private String mapOp(String op) {
        return switch (op != null ? op : "$eq") {
            case "$eq" -> "=";
            case "$neq" -> "!=";
            case "$gt" -> ">";
            case "$gte" -> ">=";
            case "$lt" -> "<";
            case "$lte" -> "<=";
            case "$like" -> "LIKE";
            case "$in" -> "IN";
            default -> "=";
        };
    }

    private Map<String, String> resolveColumns(List<String> fields, ResourceType type) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> declared = new HashSet<>();
        for (Field f : type.getFields()) {
            declared.add(f.name());
        }
        String fqn = (type.getDomain() != null ? type.getDomain() : "default") + "." + type.getName();
        for (String field : fields) {
            if (!declared.contains(field)) {
                throw new QueryParseException(
                        "Field '" + field + "' not declared on type '" + type.getName() + "'");
            }
            var rule = mappingRepo.findByTypeAndField(fqn, field);
            if (rule.isPresent()) {
                String colFQN = rule.get().getColumnFQN();
                String[] parts = colFQN.split("\\.");
                result.put(field, parts.length > 0 ? parts[parts.length - 1] : field);
            } else {
                result.put(field, field);
            }
        }
        return result;
    }
}
