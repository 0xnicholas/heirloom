package com.heirloom.schema.guard;

import com.heirloom.schema.domain.*;
import com.heirloom.security.domain.StateGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StateMachineGuard")
class StateMachineGuardTest {

    private ResourceType type;

    @BeforeEach
    void setUp() {
        type = new ResourceType("Customer");
        type.setStateMachine(List.of(
                new StateTransition("Draft", "Active"),
                new StateTransition("Active", "Frozen"),
                new StateTransition("Frozen", "Active", "unfreeze")
        ));
    }

    @Nested
    @DisplayName("guardTransition")
    class GuardTransition {

        @Test
        @DisplayName("allows legal transition")
        void allowsLegal() {
            assertThatCode(() ->
                    StateMachineGuard.guardTransition(type, "Draft", "Active"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects illegal transition")
        void rejectsIllegal() {
            assertThatThrownBy(() ->
                    StateMachineGuard.guardTransition(type, "Draft", "Frozen"))
                    .isInstanceOf(StateGuardException.class)
                    .hasMessageContaining("Draft → Frozen")
                    .hasMessageContaining("not allowed");
        }
    }

    @Nested
    @DisplayName("guardGate")
    class GuardGate {

        @Test
        @DisplayName("rejects when resource state does not match gate fromState")
        void rejectsStateMismatch() {
            StateGate gate = new StateGate("Active", null);
            assertThatThrownBy(() ->
                    StateMachineGuard.guardGate(type, "Draft", gate))
                    .isInstanceOf(StateGuardException.class)
                    .hasMessageContaining("Draft")
                    .hasMessageContaining("requires 'Active'");
        }

        @Test
        @DisplayName("rejects when gate fromState is not in state machine")
        void rejectsInvalidFromState() {
            StateGate gate = new StateGate("NopeState", null);
            assertThatThrownBy(() ->
                    StateMachineGuard.guardGate(type, "Draft", gate))
                    .isInstanceOf(StateGuardException.class)
                    .hasMessageContaining("NopeState")
                    .hasMessageContaining("not a valid state");
        }

        @Test
        @DisplayName("allows matching state with null toState")
        void allowsMatchingState() {
            StateGate gate = new StateGate("Active", null);
            assertThatCode(() ->
                    StateMachineGuard.guardGate(type, "Active", gate))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("allows matching state with valid transition")
        void allowsValidTransition() {
            StateGate gate = new StateGate("Draft", "Active");
            assertThatCode(() ->
                    StateMachineGuard.guardGate(type, "Draft", gate))
                    .doesNotThrowAnyException();
        }
    }
}
