package com.heirloom.discovery.inference.rules;

import com.heirloom.discovery.inference.InferenceRule;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.discovery.model.RawSchema;
import com.heirloom.discovery.model.RawTable;
import com.heirloom.schema.domain.Field;
import com.heirloom.schema.domain.FieldType;
import java.util.*;
import java.util.stream.Collectors;

public class FieldMapperInference implements InferenceRule {
    @Override public Confidence confidence() { return Confidence.HIGH; }

    @Override
    public List<ResourceTypeProposal> infer(RawSchema schema) {
        return schema.tables().stream().map(t -> {
            List<Field> fields = t.columns().stream()
                .map(c -> new Field(toCamelCase(c.columnName()), mapType(c.rawType()), !c.nullable()))
                .collect(Collectors.toList());
            return new ResourceTypeProposal(toPascalCase(t.tableName()), t.schemaName() + "." + t.tableName(),
                fields, List.of(), List.of(), List.of(), null,
                Confidence.HIGH, Confidence.NONE, Confidence.NONE, Confidence.NONE);
        }).collect(Collectors.toList());
    }

    private FieldType mapType(String raw) {
        return switch (raw.toLowerCase()) {
            case "integer", "bigint", "smallint", "serial", "bigserial", "numeric", "decimal", "real", "double precision" -> FieldType.NUMBER;
            case "boolean" -> FieldType.BOOLEAN;
            case "timestamp", "timestamptz", "date", "time" -> FieldType.TIMESTAMP;
            default -> FieldType.STRING;
        };
    }

    private String toCamelCase(String snake) {
        String[] parts = snake.split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++)
            sb.append(parts[i].substring(0, 1).toUpperCase()).append(parts[i].substring(1).toLowerCase());
        return sb.toString();
    }

    private String toPascalCase(String snake) {
        return Arrays.stream(snake.split("_"))
            .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
            .collect(Collectors.joining());
    }
}
