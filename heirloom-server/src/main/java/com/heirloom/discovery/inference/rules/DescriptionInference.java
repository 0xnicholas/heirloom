package com.heirloom.discovery.inference.rules;

import com.heirloom.discovery.inference.InferenceRule;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.discovery.model.RawTable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates human-readable descriptions for discovered ResourceTypes.
 * Phase 3: can use LLM backend for richer descriptions.
 * Phase 3 fallback: template-based from table name and columns.
 */
public class DescriptionInference implements InferenceRule {
    @Override public Confidence confidence() { return Confidence.LOW; }

    @Override
    public List<ResourceTypeProposal> infer(RawSchema schema) {
        return schema.tables().stream().map(t -> {
            String desc = generateDescription(t);
            return new ResourceTypeProposal(
                toPascalCase(t.tableName()), t.schemaName() + "." + t.tableName(),
                List.of(), List.of(), List.of(), List.of(), desc,
                Confidence.NONE, Confidence.NONE, Confidence.NONE, Confidence.NONE);
        }).collect(Collectors.toList());
    }

    private String generateDescription(RawTable table) {
        int colCount = table.columns().size();
        String colList = table.columns().stream()
            .limit(3).map(c -> c.columnName())
            .collect(Collectors.joining(", "));
        String suffix = colCount > 3 ? " and " + (colCount - 3) + " more fields" : "";
        return String.format("Auto-discovered from table %s. Contains fields: %s%s.",
            table.tableName(), colList, suffix);
    }

    private String toPascalCase(String snake) {
        return Arrays.stream(snake.split("_"))
            .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
            .collect(Collectors.joining());
    }
}
