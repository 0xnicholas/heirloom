package com.heirloom.query;

import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.Field;
import com.heirloom.schema.domain.ResourceType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Parses existing v1 JSON DSL into a validated SemanticQuery object.
 * Validates: type exists in Schema Registry, fields are declared, operations are legal.
 */
@Component
public class QueryParser {

    private static final Set<String> VALID_OPS = Set.of(
            "$eq", "$neq", "$gt", "$gte", "$lt", "$lte", "$in", "$like", "$and", "$or");

    private final TypeRepository typeRepo;

    public QueryParser(TypeRepository typeRepo) {
        this.typeRepo = typeRepo;
    }

    @SuppressWarnings("unchecked")
    public SemanticQuery parse(Map<String, Object> request) {
        String type = (String) request.get("type");
        if (type == null || type.isBlank()) {
            throw new QueryParseException("Query 'type' is required");
        }

        ResourceType resourceType = typeRepo.findByName(type)
                .orElseThrow(() -> new QueryParseException(
                        "Type '" + type + "' not found in Schema Registry"));

        List<String> fields = (List<String>) request.getOrDefault("fields", List.of());
        validateFields(fields, resourceType);

        Map<String, Object> filter = (Map<String, Object>) request.get("filter");
        if (filter != null) {
            validateFilter(filter, resourceType);
        }

        List<Map<String, Object>> traverse = (List<Map<String, Object>>) request.get("traverse");
        Map<String, Object> aggregate = (Map<String, Object>) request.get("aggregate");
        String sortField = request.containsKey("sort") 
                ? (String) ((Map<String, Object>) request.get("sort")).get("field") : null;
        String sortDir = request.containsKey("sort")
                ? (String) ((Map<String, Object>) request.get("sort")).getOrDefault("direction", "asc") : null;
        int limit = request.containsKey("limit") ? ((Number) request.get("limit")).intValue() : 20;
        int offset = request.containsKey("offset") ? ((Number) request.get("offset")).intValue() : 0;

        return new SemanticQuery(type, fields, filter, traverse, aggregate,
                sortField, sortDir, limit, offset);
    }

    private void validateFields(List<String> fields, ResourceType type) {
        Set<String> declared = new HashSet<>();
        for (Field f : type.getFields()) {
            declared.add(f.name());
        }
        for (String field : fields) {
            if (!declared.contains(field)) {
                throw new QueryParseException(
                        "Field '" + field + "' is not declared on type '"
                        + type.getName() + "'. Declared fields: " + declared);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateFilter(Map<String, Object> filter, ResourceType type) {
        String field = (String) filter.get("field");
        String op = (String) filter.get("op");

        if (field != null) {
            // Simple filter: { field, op, value }
            if (op != null && !VALID_OPS.contains(op)) {
                throw new QueryParseException("Unknown filter operator: " + op);
            }
            validateFieldName(field, type);
        } else {
            // Compound filter: { $and: [...], $or: [...] }
            for (var entry : filter.entrySet()) {
                String key = entry.getKey();
                if (!VALID_OPS.contains(key)) {
                    throw new QueryParseException("Unknown filter operator: " + key);
                }
                if (entry.getValue() instanceof List<?> subFilters) {
                    for (Object sub : subFilters) {
                        if (sub instanceof Map) {
                            validateFilter((Map<String, Object>) sub, type);
                        }
                    }
                }
            }
        }
    }

    private void validateFieldName(String fieldName, ResourceType type) {
        boolean exists = type.getFields().stream()
                .anyMatch(f -> f.name().equals(fieldName));
        if (!exists) {
            throw new QueryParseException(
                    "Filter field '" + fieldName + "' is not declared on type '"
                    + type.getName() + "'");
        }
    }
}
