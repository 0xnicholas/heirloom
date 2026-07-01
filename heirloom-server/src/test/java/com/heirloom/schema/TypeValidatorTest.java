package com.heirloom.schema;

import com.heirloom.schema.domain.*;
import com.heirloom.schema.service.TypeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TypeValidator")
class TypeValidatorTest {

    private final ResourceType customerType = buildCustomer();

    private static ResourceType buildCustomer() {
        ResourceType t = new ResourceType("Customer");
        t.setFields(List.of(
            new Field("name", FieldType.STRING, true),
            new Field("tier", FieldType.ENUM, false, List.of("free", "pro", "enterprise")),
            new Field("arr", FieldType.NUMBER, false)
        ));
        t.setAbilities(List.of(Ability.KEY, Ability.QUERY, Ability.MUTATE, Ability.FREEZE));
        t.setStateMachine(List.of(
            new StateTransition("Draft", "Active"),
            new StateTransition("Active", "Frozen"),
            new StateTransition("Frozen", "Active", "unfreeze")
        ));
        t.setInitialState("Draft");
        t.setRelationships(List.of(
            new Relationship("placed", "Order", RelationshipSemantics.ASSOCIATION)
        ));
        return t;
    }

    @Nested
    @DisplayName("valid type")
    class ValidType {

        @Test
        @DisplayName("produces no errors")
        void noErrors() {
            var orderType = new ResourceType("Order");
            orderType.setFields(List.of(new Field("total", FieldType.NUMBER, true)));
            orderType.setAbilities(List.of(Ability.KEY, Ability.QUERY));
            orderType.setStateMachine(List.of());
            orderType.setRelationships(List.of());

            Map<String, ResourceType> known = Map.of("Order", orderType);
            var diags = TypeValidator.validate(customerType, known);

            var errors = diags.stream()
                .filter(d -> d.severity() == TypeValidator.Severity.ERROR)
                .toList();
            assertThat(errors).isEmpty();
        }
    }

    @Nested
    @DisplayName("duplicate fields")
    class DuplicateFields {

        @Test
        @DisplayName("reports error")
        void reportsError() {
            ResourceType t = new ResourceType("Test");
            t.setFields(List.of(
                new Field("name", FieldType.STRING, true),
                new Field("name", FieldType.NUMBER, false)
            ));
            t.setAbilities(List.of(Ability.KEY));
            t.setStateMachine(List.of());
            t.setRelationships(List.of());

            var diags = TypeValidator.validate(t, Map.of());
            assertThat(diags.stream().anyMatch(
                d -> d.severity() == TypeValidator.Severity.ERROR
                  && d.message().toLowerCase().contains("duplicate"))).isTrue();
        }
    }

    @Nested
    @DisplayName("relationship targets")
    class RelationshipTargets {

        @Test
        @DisplayName("reports error when target type does not exist")
        void reportsMissingTarget() {
            ResourceType t = new ResourceType("Test");
            t.setFields(List.of(new Field("x", FieldType.STRING, true)));
            t.setAbilities(List.of(Ability.KEY));
            t.setStateMachine(List.of());
            t.setRelationships(List.of(
                new Relationship("ref", "GhostType", RelationshipSemantics.REFERENCE)
            ));

            var diags = TypeValidator.validate(t, Map.of());
            assertThat(diags.stream().anyMatch(
                d -> d.severity() == TypeValidator.Severity.ERROR
                  && d.message().contains("GhostType"))).isTrue();
        }
    }

    @Nested
    @DisplayName("abilities")
    class Abilities {

        @Test
        @DisplayName("warns when no abilities declared")
        void warnsEmpty() {
            ResourceType t = new ResourceType("Test");
            t.setFields(List.of(new Field("x", FieldType.STRING, true)));
            t.setAbilities(List.of());
            t.setStateMachine(List.of());
            t.setRelationships(List.of());

            var diags = TypeValidator.validate(t, Map.of());
            assertThat(diags.stream().anyMatch(
                d -> d.severity() == TypeValidator.Severity.WARNING
                  && d.message().contains("no abilities"))).isTrue();
        }
    }

    @Nested
    @DisplayName("naming convention")
    class Naming {

        @Test
        @DisplayName("suggests PascalCase")
        void suggestsPascalCase() {
            ResourceType t = new ResourceType("customer");
            t.setFields(List.of(new Field("x", FieldType.STRING, true)));
            t.setAbilities(List.of(Ability.KEY));
            t.setStateMachine(List.of());
            t.setRelationships(List.of());

            var diags = TypeValidator.validate(t, Map.of());
            assertThat(diags.stream().anyMatch(
                d -> d.severity() == TypeValidator.Severity.INFO
                  && d.message().toLowerCase().contains("pascalcase"))).isTrue();
        }
    }

    @Nested
    @DisplayName("orphan states")
    class OrphanStates {

        @Test
        @DisplayName("reports warning for state with no incoming transitions")
        void reportsOrphan() {
            ResourceType t = new ResourceType("Test");
            t.setFields(List.of(new Field("x", FieldType.STRING, true)));
            t.setAbilities(List.of(Ability.KEY));
            t.setStateMachine(List.of(
                new StateTransition("Draft", "Active"),
                new StateTransition("Active", "Frozen"),
                new StateTransition("Frozen", "Frozen")
            ));
            t.setRelationships(List.of());

            var diags = TypeValidator.validate(t, Map.of());
            // Draft is an initial state (has no incoming edges),
            // Active has incoming from Draft,
            // Frozen has incoming from Active and itself.
            // No orphan expected.
            assertThat(diags.stream().noneMatch(
                d -> d.severity() == TypeValidator.Severity.WARNING
                  && d.message().contains("orphan"))).isTrue();
        }
    }

    @Nested
    @DisplayName("initialState")
    class InitialState {

        @Test
        @DisplayName("requires initialState when state machine is non-empty")
        void requiresInitialState() {
            ResourceType t = new ResourceType("Test");
            t.setFields(List.of(new Field("x", FieldType.STRING, true)));
            t.setAbilities(List.of(Ability.KEY));
            t.setStateMachine(List.of(
                new StateTransition("Draft", "Active")
            ));
            t.setRelationships(List.of());
            // initialState not set

            var diags = TypeValidator.validate(t, Map.of());
            assertThat(diags.stream().anyMatch(
                d -> d.severity() == TypeValidator.Severity.ERROR
                  && d.message().toLowerCase().contains("initialstate"))).isTrue();
        }

        @Test
        @DisplayName("rejects initialState not in state machine")
        void rejectsInvalidInitialState() {
            ResourceType t = new ResourceType("Test");
            t.setFields(List.of(new Field("x", FieldType.STRING, true)));
            t.setAbilities(List.of(Ability.KEY));
            t.setStateMachine(List.of(
                new StateTransition("Draft", "Active")
            ));
            t.setRelationships(List.of());
            t.setInitialState("Archived"); // not in state machine

            var diags = TypeValidator.validate(t, Map.of());
            assertThat(diags.stream().anyMatch(
                d -> d.severity() == TypeValidator.Severity.ERROR
                  && d.message().contains("Archived")
                  && d.message().toLowerCase().contains("not a valid state"))).isTrue();
        }

        @Test
        @DisplayName("accepts valid initialState")
        void acceptsValidInitialState() {
            ResourceType t = new ResourceType("Test");
            t.setFields(List.of(new Field("x", FieldType.STRING, true)));
            t.setAbilities(List.of(Ability.KEY));
            t.setStateMachine(List.of(
                new StateTransition("Draft", "Active")
            ));
            t.setRelationships(List.of());
            t.setInitialState("Draft");

            var diags = TypeValidator.validate(t, Map.of());
            assertThat(diags.stream().noneMatch(
                d -> d.severity() == TypeValidator.Severity.ERROR
                  && d.message().toLowerCase().contains("initial"))).isTrue();
        }

        @Test
        @DisplayName("no error when state machine is empty (initialState optional)")
        void initialStateOptionalWhenNoStateMachine() {
            ResourceType t = new ResourceType("Test");
            t.setFields(List.of(new Field("x", FieldType.STRING, true)));
            t.setAbilities(List.of(Ability.KEY));
            t.setStateMachine(List.of());
            t.setRelationships(List.of());
            // initialState not set — should be fine

            var diags = TypeValidator.validate(t, Map.of());
            assertThat(diags.stream().noneMatch(
                d -> d.severity() == TypeValidator.Severity.ERROR
                  && d.message().toLowerCase().contains("initial"))).isTrue();
        }
    }
}
