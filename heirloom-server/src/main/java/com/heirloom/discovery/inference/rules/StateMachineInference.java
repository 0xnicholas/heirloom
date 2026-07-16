package com.heirloom.discovery.inference.rules;

import com.heirloom.discovery.inference.InferenceRule;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.core.discovery.model.RawConstraint;
import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.discovery.model.RawTable;
import com.heirloom.schema.domain.StateTransition;
import java.util.*;
import java.util.stream.Collectors;

public class StateMachineInference implements InferenceRule {
    @Override public Confidence confidence() { return Confidence.LOW; }

    @Override
    public List<ResourceTypeProposal> infer(RawSchema schema) {
        return schema.tables().stream()
            .filter(t -> hasStatusColumn(t))
            .map(t -> {
                List<StateTransition> transitions = inferTransitions(t);
                return new ResourceTypeProposal(
                    toPascalCase(t.tableName()), t.schemaName() + "." + t.tableName(),
                    List.of(), List.of(), List.of(), transitions, null,
                    Confidence.NONE, Confidence.NONE, Confidence.NONE, Confidence.LOW);
            })
            .filter(p -> !p.stateMachine().isEmpty())
            .collect(Collectors.toList());
    }

    private boolean hasStatusColumn(RawTable table) {
        return table.columns().stream()
            .anyMatch(c -> c.columnName().equalsIgnoreCase("status")
                || c.columnName().equalsIgnoreCase("state"));
    }

    private List<StateTransition> inferTransitions(RawTable table) {
        // Check CHECK constraints for state values
        for (RawConstraint c : table.constraints()) {
            if (c.type() == RawConstraint.ConstraintType.CHECK && c.checkExpression() != null) {
                List<String> states = parseStateValues(c.checkExpression());
                if (states.size() >= 2) {
                    return buildLinearTransitions(states);
                }
            }
        }

        // Check for ENUM column named "status" or "state"
        var statusCol = table.columns().stream()
            .filter(c -> (c.columnName().equalsIgnoreCase("status") || c.columnName().equalsIgnoreCase("state"))
                && c.rawType() != null && c.rawType().toLowerCase().contains("enum"))
            .findFirst();

        // Fallback: use common state names
        if (statusCol.isPresent()) {
            return buildLinearTransitions(List.of("draft", "active", "archived"));
        }

        return List.of();
    }

    private List<String> parseStateValues(String expression) {
        // Parse CHECK like: status IN ('draft','active','frozen')
        // or: status = ANY (ARRAY['draft','active'])
        List<String> values = new ArrayList<>();
        int inIdx = expression.toUpperCase().indexOf("IN");
        if (inIdx > 0) {
            String afterIn = expression.substring(inIdx + 2).trim();
            // Extract quoted values
            int start = afterIn.indexOf('(');
            int end = afterIn.lastIndexOf(')');
            if (start >= 0 && end > start) {
                String content = afterIn.substring(start + 1, end);
                for (String part : content.split(",")) {
                    String cleaned = part.trim().replaceAll("^['\"]|['\"]$", "");
                    if (!cleaned.isEmpty()) values.add(cleaned);
                }
            }
        }
        return values;
    }

    private List<StateTransition> buildLinearTransitions(List<String> states) {
        List<StateTransition> transitions = new ArrayList<>();
        for (int i = 0; i < states.size() - 1; i++) {
            transitions.add(new StateTransition(states.get(i), states.get(i + 1)));
        }
        return transitions;
    }

    private String toPascalCase(String snake) {
        return Arrays.stream(snake.split("_"))
            .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
            .collect(Collectors.joining());
    }
}
