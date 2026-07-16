package com.heirloom.discovery.inference.rules;

import com.heirloom.discovery.inference.InferenceContext;
import com.heirloom.discovery.inference.InferenceRule;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.discovery.model.RawTable;
import com.heirloom.schema.domain.Ability;
import java.util.*;
import java.util.stream.Collectors;

public class AbilityInference implements InferenceRule {
    @Override public Confidence confidence() { return Confidence.LOW; }

    @Override
    public List<ResourceTypeProposal> infer(InferenceContext ctx) {
        RawSchema schema = ctx.rawSchema();
        // Profiling enhancement (Phase 3.2): use ctx.profile().rowCount() to detect config tables → FREEZE
        return schema.tables().stream().map(t -> {
            List<Ability> abilities = new ArrayList<>(List.of(Ability.KEY, Ability.QUERY));
            
            // Non-view tables can be mutated
            abilities.add(Ability.MUTATE);

            // Naming heuristics
            String name = t.tableName().toLowerCase();
            if (name.endsWith("_log") || name.endsWith("_audit") || name.endsWith("_history")) {
                abilities.add(Ability.FREEZE);
                abilities.remove(Ability.MUTATE);
                abilities.remove(Ability.DROP);
            }

            // Junction tables: 2 FK + few non-FK columns → no DROP
            long fkCount = t.constraints().stream()
                .filter(c -> c.type() == com.heirloom.core.discovery.model.RawConstraint.ConstraintType.FOREIGN_KEY).count();
            if (fkCount >= 2 && t.columns().size() - fkCount <= 2) {
                abilities.remove(Ability.DROP);
            }

            return new ResourceTypeProposal(
                toPascalCase(t.tableName()), t.schemaName() + "." + t.tableName(),
                List.of(), List.of(), abilities, List.of(), null,
                Confidence.NONE, Confidence.NONE, Confidence.LOW, Confidence.NONE);
        }).collect(Collectors.toList());
    }

    private String toPascalCase(String snake) {
        return Arrays.stream(snake.split("_"))
            .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
            .collect(Collectors.joining());
    }
}
