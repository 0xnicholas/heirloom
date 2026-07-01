package com.heirloom.schema.guard;

import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.domain.StateMachine;
import com.heirloom.security.domain.StateGate;

/**
 * Runtime state machine enforcement.
 * <p>
 * Caller (ResourceService or Action pipeline) is responsible for resolving
 * the effective current state BEFORE calling these methods. If the resource
 * has no explicit state, the caller applies ResourceType.initialState as the
 * fallback.
 */
public final class StateMachineGuard {

    private StateMachineGuard() {}

    /**
     * Guard a direct state transition (currentState → toState).
     */
    public static void guardTransition(ResourceType type, String currentState, String toState) {
        if (!StateMachine.isValidTransition(type, currentState, toState)) {
            throw new StateGuardException(
                    "Transition '" + currentState + " → " + toState
                    + "' is not allowed for type '" + type.getName()
                    + "'. Valid from '" + currentState + "': "
                    + StateMachine.transitionsFrom(type, currentState));
        }
    }

    /**
     * Guard an action's state gate against a resource's current state.
     */
    public static void guardGate(ResourceType type, String currentState, StateGate gate) {
        if (!StateMachine.isValidState(type, gate.fromState())) {
            throw new StateGuardException(
                    "StateGate fromState '" + gate.fromState()
                    + "' is not a valid state for type '" + type.getName() + "'");
        }

        if (!currentState.equals(gate.fromState())) {
            throw new StateGuardException(
                    "Resource is in state '" + currentState
                    + "', but action requires '" + gate.fromState() + "'");
        }

        if (gate.toState() != null) {
            guardTransition(type, currentState, gate.toState());
        }
    }
}
