package com.heirloom.security.validation;

import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.*;
import com.heirloom.security.domain.Action;
import com.heirloom.security.domain.ActionInput;
import com.heirloom.security.domain.StateGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("ActionValidator")
@ExtendWith(MockitoExtension.class)
class ActionValidatorTest {

    @Mock
    private TypeRepository typeRepo;

    private ActionValidator validator;
    private ResourceType customerType;
    private Action validAction;

    @BeforeEach
    void setUp() {
        validator = new ActionValidator(typeRepo);

        customerType = new ResourceType("Customer");
        customerType.setDomain("default");
        customerType.setFields(List.of(
                new Field("name", FieldType.STRING, true),
                new Field("tier", FieldType.ENUM, false, List.of("free", "pro", "enterprise")),
                new Field("arr", FieldType.NUMBER, false)
        ));
        customerType.setAbilities(List.of(Ability.KEY, Ability.QUERY, Ability.MUTATE, Ability.FREEZE));
        customerType.setStateMachine(List.of(
                new StateTransition("Draft", "Active"),
                new StateTransition("Active", "Frozen"),
                new StateTransition("Frozen", "Active", "unfreeze")
        ));
        customerType.setInitialState("Draft");

        validAction = new Action();
        validAction.setName("update_customer_tier");
        validAction.setTargetType("Customer");
        validAction.setTargetTypeFqn("default.Customer");
        validAction.setRequiredAbilityEnum("MUTATE");
        validAction.setStateGateJson(new StateGate("Active", null));
        validAction.setInputSchemaJson(List.of(
                new ActionInput("tier", FieldType.ENUM, true)
        ));
    }

    @Nested
    @DisplayName("ability gate")
    class AbilityGate {

        @Test
        @DisplayName("rejects when target type does not declare the required ability")
        void rejectsUndeclaredAbility() {
            validAction.setRequiredAbilityEnum("DROP");
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> validator.validate(validAction))
                    .isInstanceOf(ActionValidationException.class)
                    .hasMessageContaining("DROP")
                    .hasMessageContaining("Customer");
        }

        @Test
        @DisplayName("allows when ability is declared")
        void allowsDeclaredAbility() {
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatCode(() -> validator.validate(validAction)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("allows null ability (notification actions)")
        void allowsNullAbility() {
            validAction.setRequiredAbilityEnum(null);
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatCode(() -> validator.validate(validAction)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("state gate")
    class StateGateTests {

        @Test
        @DisplayName("rejects when fromState is not a valid state")
        void rejectsInvalidFromState() {
            validAction.setStateGateJson(new StateGate("NopeState", null));
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> validator.validate(validAction))
                    .isInstanceOf(ActionValidationException.class)
                    .hasMessageContaining("NopeState")
                    .hasMessageContaining("not a valid state");
        }

        @Test
        @DisplayName("rejects when transition is not defined")
        void rejectsInvalidTransition() {
            validAction.setStateGateJson(new StateGate("Draft", "Frozen"));
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> validator.validate(validAction))
                    .isInstanceOf(ActionValidationException.class)
                    .hasMessageContaining("Draft")
                    .hasMessageContaining("not defined");
        }

        @Test
        @DisplayName("allows valid fromState with null toState")
        void allowsValidFromOnly() {
            validAction.setStateGateJson(new StateGate("Active", null));
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatCode(() -> validator.validate(validAction)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("allows valid transition")
        void allowsValidTransition() {
            validAction.setStateGateJson(new StateGate("Draft", "Active"));
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatCode(() -> validator.validate(validAction)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("input schema")
    class InputSchema {

        @Test
        @DisplayName("rejects undeclared input field")
        void rejectsUndeclaredField() {
            validAction.setInputSchemaJson(List.of(
                    new ActionInput("ssn", FieldType.STRING, true)
            ));
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> validator.validate(validAction))
                    .isInstanceOf(ActionValidationException.class)
                    .hasMessageContaining("ssn")
                    .hasMessageContaining("not declared");
        }

        @Test
        @DisplayName("rejects type mismatch")
        void rejectsTypeMismatch() {
            validAction.setInputSchemaJson(List.of(
                    new ActionInput("arr", FieldType.STRING, true)
            ));
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> validator.validate(validAction))
                    .isInstanceOf(ActionValidationException.class)
                    .hasMessageContaining("arr")
                    .hasMessageContaining("type mismatch");
        }

        @Test
        @DisplayName("allows valid inputs")
        void allowsValidInputs() {
            when(typeRepo.findByName("default.Customer")).thenReturn(Optional.of(customerType));

            assertThatCode(() -> validator.validate(validAction)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("target type")
    class TargetType {

        @Test
        @DisplayName("rejects unknown target type")
        void rejectsUnknownType() {
            validAction.setTargetTypeFqn("default.Nonexistent");
            when(typeRepo.findByName("default.Nonexistent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> validator.validate(validAction))
                    .isInstanceOf(ActionValidationException.class)
                    .hasMessageContaining("Nonexistent")
                    .hasMessageContaining("not found");
        }
    }
}
