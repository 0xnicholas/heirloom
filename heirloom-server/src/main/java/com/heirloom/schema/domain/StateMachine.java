package com.heirloom.schema.domain;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared state machine utility — used by I-0 (ResourceService),
 * I-1 (ActionValidator), and I-2 (StateMachineGuard).
 */
public final class StateMachine {

    private StateMachine() {}

    /**
     * All state names reachable in this type's state machine.
     */
    public static Set<String> allStates(ResourceType type) {
        Set<String> states = new LinkedHashSet<>();
        for (StateTransition t : type.getStateMachine()) {
            states.add(t.from());
            states.add(t.to());
        }
        return states;
    }

    /**
     * Whether a given state name exists in the type's state machine.
     */
    public static boolean isValidState(ResourceType type, String stateName) {
        return allStates(type).contains(stateName);
    }

    /**
     * Whether a specific transition edge exists.
     */
    public static boolean isValidTransition(ResourceType type, String from, String to) {
        return type.getStateMachine().stream()
                .anyMatch(t -> t.from().equals(from) && t.to().equals(to));
    }

    /**
     * All legal target states from a given state.
     */
    public static List<String> transitionsFrom(ResourceType type, String from) {
        return type.getStateMachine().stream()
                .filter(t -> t.from().equals(from))
                .map(t -> t.from() + " → " + t.to())
                .collect(Collectors.toList());
    }
}
