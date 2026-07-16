package com.heirloom.discovery.inference.rules;

import com.heirloom.discovery.inference.InferenceRule;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.core.discovery.model.RawConstraint;
import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.discovery.model.RawTable;
import com.heirloom.schema.domain.Relationship;
import com.heirloom.schema.domain.RelationshipSemantics;

import java.util.*;
import java.util.stream.Collectors;

public class RelationshipInference implements InferenceRule {
    @Override public Confidence confidence() { return Confidence.MEDIUM; }

    @Override
    public List<ResourceTypeProposal> infer(RawSchema schema) {
        // Build type name mapping: sourceTable → proposedTypeName
        Map<String, String> typeNames = new HashMap<>();
        for (RawTable t : schema.tables()) {
            typeNames.put(t.schemaName() + "." + t.tableName(), toPascalCase(t.tableName()));
        }

        // Detect junction tables: tables with exactly 2 FK constraints and few non-FK columns
        Set<String> junctionTables = detectJunctionTables(schema);

        List<ResourceTypeProposal> results = new ArrayList<>();

        for (RawTable table : schema.tables()) {
            String sourceType = typeNames.get(table.schemaName() + "." + table.tableName());
            List<Relationship> relationships = new ArrayList<>();

            for (RawConstraint c : table.constraints()) {
                if (c.type() != RawConstraint.ConstraintType.FOREIGN_KEY) continue;

                String targetTableKey = "public." + c.targetTable(); // simplified: assume public schema
                String targetType = typeNames.get(targetTableKey);
                if (targetType == null) continue;

                String label = deriveLabel(c.columns());
                RelationshipSemantics semantics = inferSemantics(c, junctionTables.contains(
                    table.schemaName() + "." + table.tableName()));

                relationships.add(new Relationship(label, targetType, semantics));
            }

            if (!relationships.isEmpty()) {
                results.add(new ResourceTypeProposal(sourceType,
                    table.schemaName() + "." + table.tableName(),
                    List.of(), relationships, List.of(), List.of(), null,
                    Confidence.NONE, Confidence.MEDIUM, Confidence.NONE, Confidence.NONE));
            }
        }

        return results;
    }

    private RelationshipSemantics inferSemantics(RawConstraint c, boolean isJunctionTable) {
        if (isJunctionTable) return RelationshipSemantics.ASSOCIATION;
        String rule = c.deleteRule();
        if (rule == null) return RelationshipSemantics.REFERENCE;
        return switch (rule.toUpperCase()) {
            case "CASCADE" -> RelationshipSemantics.OWNERSHIP;
            case "SET NULL", "SET DEFAULT" -> RelationshipSemantics.REFERENCE;
            default -> RelationshipSemantics.REFERENCE;
        };
    }

    private Set<String> detectJunctionTables(RawSchema schema) {
        Set<String> junction = new HashSet<>();
        for (RawTable table : schema.tables()) {
            long fkCount = table.constraints().stream()
                .filter(c -> c.type() == RawConstraint.ConstraintType.FOREIGN_KEY).count();
            long nonFkColumns = table.columns().size() - fkCount;
            if (fkCount == 2 && nonFkColumns <= 2) {
                junction.add(table.schemaName() + "." + table.tableName());
            }
        }
        return junction;
    }

    private String deriveLabel(List<String> columns) {
        if (columns == null || columns.isEmpty()) return "relates_to";
        String col = columns.get(0);
        // Strip _id suffix for readability: customer_id → customer, order_id → order
        if (col.endsWith("_id")) col = col.substring(0, col.length() - 3);
        return col;
    }

    private String toPascalCase(String snake) {
        return Arrays.stream(snake.replace(".", "_").split("_"))
            .filter(s -> !s.isEmpty())
            .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
            .collect(Collectors.joining());
    }
}
