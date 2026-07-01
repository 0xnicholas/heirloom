package com.heirloom.schema.service;

import com.heirloom.schema.domain.*;
import com.heirloom.schema.repository.ResourceTypeRepository;

import java.util.*;

/**
 * Validates a Resource Type definition against Heirloom's semantic rules.
 * Mirrors the TypeScript type-validator.ts from the Workshop frontend.
 *
 * <p>Key rules:
 * <ul>
 *   <li>Field name uniqueness within a type</li>
 *   <li>Relationship targets must exist in the registry</li>
 *   <li>Orphan state nodes (no incoming transitions) produce a warning</li>
 *   <li>Empty abilities list produces a warning</li>
 *   <li>PascalCase naming convention (info-level)</li>
 *   <li>Initial state required when state machine is non-empty (I-0)</li>
 *   <li>Initial state must be a valid state in the state machine (I-0)</li>
 * </ul>
 */
public class TypeValidator {

    /**
     * Validates a resource type definition. Returns diagnostics
     * keyed by severity level.
     */
    public static List<Diagnostic> validate(ResourceType type,
                                            Map<String, ResourceType> knownTypes) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        checkDuplicateFields(type, diagnostics);
        checkRelationshipTargets(type, knownTypes, diagnostics);
        checkOrphanStates(type, diagnostics);
        checkAbilities(type, diagnostics);
        checkNaming(type, diagnostics);
        checkInitialState(type, diagnostics);

        return diagnostics;
    }

    private static void checkDuplicateFields(ResourceType type, List<Diagnostic> out) {
        Set<String> seen = new HashSet<>();
        for (Field field : type.getFields()) {
            if (!seen.add(field.name())) {
                out.add(new Diagnostic(Severity.ERROR,
                        "Duplicate field name: \"" + field.name() + "\""));
            }
        }
    }

    private static void checkRelationshipTargets(ResourceType type,
                                                  Map<String, ResourceType> knownTypes,
                                                  List<Diagnostic> out) {
        for (Relationship rel : type.getRelationships()) {
            if (!knownTypes.containsKey(rel.targetType())) {
                out.add(new Diagnostic(Severity.ERROR,
                        "Relationship \"" + rel.label()
                        + "\" references non-existent type \""
                        + rel.targetType() + "\""));
            }
        }
    }

    private static void checkOrphanStates(ResourceType type, List<Diagnostic> out) {
        Set<String> allStates = new HashSet<>();
        Set<String> hasIncoming = new HashSet<>();
        Set<String> initialStates = new HashSet<>();

        for (StateTransition t : type.getStateMachine()) {
            allStates.add(t.from());
            allStates.add(t.to());
            hasIncoming.add(t.to());
        }

        // States that appear as 'from' but never as 'to' are initial states
        for (StateTransition t : type.getStateMachine()) {
            if (!hasIncoming.contains(t.from())) {
                initialStates.add(t.from());
            }
        }

        for (String state : allStates) {
            if (!hasIncoming.contains(state) && !initialStates.contains(state)) {
                out.add(new Diagnostic(Severity.WARNING,
                        "State \"" + state
                        + "\" has no incoming transitions (orphan node)"));
            }
        }
    }

    private static void checkAbilities(ResourceType type, List<Diagnostic> out) {
        if (type.getAbilities().isEmpty()) {
            out.add(new Diagnostic(Severity.WARNING,
                    "Type \"" + type.getName()
                    + "\" has no abilities declared — "
                    + "it cannot be queried or modified"));
        }
    }

    private static void checkNaming(ResourceType type, List<Diagnostic> out) {
        String name = type.getName();
        if (name != null && !name.isEmpty() && !Character.isUpperCase(name.charAt(0))) {
            out.add(new Diagnostic(Severity.INFO,
                    "Type name \"" + name
                    + "\" should use PascalCase by convention"));
        }
    }

    private static void checkInitialState(ResourceType type, List<Diagnostic> out) {
        boolean hasStateMachine = type.getStateMachine() != null && !type.getStateMachine().isEmpty();
        String initialState = type.getInitialState();

        if (hasStateMachine && (initialState == null || initialState.isBlank())) {
            out.add(new Diagnostic(Severity.ERROR,
                    "Type \"" + type.getName()
                    + "\" has a state machine but no initialState declared. "
                    + "Declare an initialState that is a valid state in the state machine."));
            return;
        }

        if (initialState != null && !initialState.isBlank() && hasStateMachine) {
            Set<String> allStates = new HashSet<>();
            for (StateTransition t : type.getStateMachine()) {
                allStates.add(t.from());
                allStates.add(t.to());
            }
            if (!allStates.contains(initialState)) {
                out.add(new Diagnostic(Severity.ERROR,
                        "Initial state \"" + initialState
                        + "\" is not a valid state in type \"" + type.getName()
                        + "\" state machine. Valid states: " + allStates));
            }
        }
    }

    // --- Diagnostic nested types ---

    public enum Severity { ERROR, WARNING, INFO }

    public record Diagnostic(Severity severity, String message) {
        public String getField() { return null; }
    }
}
