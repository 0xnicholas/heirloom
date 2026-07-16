package com.heirloom.discovery.inference.rules;

import com.heirloom.discovery.inference.InferenceContext;
import com.heirloom.discovery.inference.InferenceRule;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.discovery.model.RawTable;
import java.util.*;
import java.util.stream.Collectors;

public class TypeNameInference implements InferenceRule {
    @Override public Confidence confidence() { return Confidence.HIGH; }

    @Override
    public List<ResourceTypeProposal> infer(InferenceContext ctx) {
        RawSchema schema = ctx.rawSchema();
        return schema.tables().stream()
            .map(t -> new ResourceTypeProposal(toPascalCase(t.tableName()), t.schemaName() + "." + t.tableName(),
                List.of(), List.of(), List.of(), List.of(), t.comment(),
                Confidence.HIGH, Confidence.NONE, Confidence.NONE, Confidence.NONE))
            .collect(Collectors.toList());
    }

    private String toPascalCase(String snake) {
        return Arrays.stream(snake.split("_"))
            .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
            .collect(Collectors.joining());
    }
}
