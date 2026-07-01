package com.heirloom.service;

import com.heirloom.domain.Resource;
import com.heirloom.repository.ResourceRepository;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("ResourceService")
@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    @Mock
    private ResourceRepository resourceRepo;

    @Mock
    private TypeRepository typeRepo;

    private ResourceService service;

    private ResourceType customerType;

    @BeforeEach
    void setUp() {
        service = new ResourceService(resourceRepo, typeRepo);

        customerType = new ResourceType("Customer");
        customerType.setDomain("default");
        customerType.setFields(List.of(
                new Field("name", FieldType.STRING, true),
                new Field("tier", FieldType.ENUM, false, List.of("free", "pro", "enterprise")),
                new Field("arr", FieldType.NUMBER, false)
        ));
        customerType.setAbilities(List.of(Ability.KEY, Ability.QUERY, Ability.MUTATE));
        customerType.setStateMachine(List.of(
                new StateTransition("Draft", "Active"),
                new StateTransition("Active", "Frozen")
        ));
        customerType.setInitialState("Draft");
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("creates resource with valid fields and initialState")
        void createsValidResource() {
            when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));
            when(resourceRepo.create(any(Resource.class))).thenAnswer(inv -> inv.getArgument(0));

            Resource r = service.create("Customer", "agent-123",
                    Map.of("name", "Acme Corp", "tier", "enterprise"));

            assertThat(r.getRid()).startsWith("default.Customer.");
            assertThat(r.getResourceType()).isEqualTo("Customer");
            assertThat(r.getOwner()).isEqualTo("agent-123");
            assertThat(r.getCurrentState()).isEqualTo("Draft");
            assertThat(r.getFields()).containsEntry("name", "Acme Corp");
        }

        @Test
        @DisplayName("rejects unknown resource type")
        void rejectsUnknownType() {
            when(typeRepo.findByName("Nonexistent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create("Nonexistent", "agent", Map.of()))
                    .isInstanceOf(ResourceValidationException.class)
                    .hasMessageContaining("Nonexistent")
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("rejects undeclared field")
        void rejectsUndeclaredField() {
            when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> service.create("Customer", "agent",
                    Map.of("ssn", "123-45-6789")))
                    .isInstanceOf(ResourceValidationException.class)
                    .hasMessageContaining("ssn")
                    .hasMessageContaining("not declared");
        }

        @Test
        @DisplayName("rejects field type mismatch")
        void rejectsTypeMismatch() {
            when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> service.create("Customer", "agent",
                    Map.of("arr", "not-a-number")))
                    .isInstanceOf(ResourceValidationException.class)
                    .hasMessageContaining("arr")
                    .hasMessageContaining("type mismatch");
        }

        @Test
        @DisplayName("rejects when type has no initialState")
        void rejectsNoInitialState() {
            ResourceType noInit = new ResourceType("NoInit");
            noInit.setDomain("default");
            noInit.setStateMachine(List.of(
                    new StateTransition("A", "B")
            ));
            // initialState not set

            when(typeRepo.findByName("NoInit")).thenReturn(Optional.of(noInit));

            assertThatThrownBy(() -> service.create("NoInit", "agent", Map.of()))
                    .isInstanceOf(ResourceValidationException.class)
                    .hasMessageContaining("initialState");
        }
    }

    @Nested
    @DisplayName("updateFields")
    class UpdateFields {

        @Test
        @DisplayName("updates fields with optimistic lock")
        void updatesFields() {
            Resource existing = new Resource("default.Customer.abc123", "Customer", "agent", "Draft");
            existing.setFields(new LinkedHashMap<>(Map.of("name", "Old Corp", "tier", "free")));
            existing.setVersion(0L);

            when(resourceRepo.findByRid("default.Customer.abc123"))
                    .thenReturn(Optional.of(existing));
            when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));
            when(resourceRepo.update(any(Resource.class))).thenAnswer(inv -> inv.getArgument(0));

            Resource updated = service.updateFields("default.Customer.abc123",
                    Map.of("tier", "enterprise"), 0L);

            assertThat(updated.getFields()).containsEntry("tier", "enterprise");
            assertThat(updated.getFields()).containsEntry("name", "Old Corp"); // preserved
        }

        @Test
        @DisplayName("rejects version conflict")
        void rejectsVersionConflict() {
            Resource existing = new Resource("default.Customer.abc123", "Customer", "agent", "Draft");
            existing.setVersion(5L);

            when(resourceRepo.findByRid("default.Customer.abc123"))
                    .thenReturn(Optional.of(existing));
            when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> service.updateFields("default.Customer.abc123",
                    Map.of("tier", "enterprise"), 0L))
                    .isInstanceOf(ResourceValidationException.class)
                    .hasMessageContaining("Version conflict");
        }
    }

    @Nested
    @DisplayName("transitionState")
    class TransitionState {

        @Test
        @DisplayName("allows legal state transition")
        void allowsLegalTransition() {
            Resource existing = new Resource("default.Customer.abc123", "Customer", "agent", "Draft");
            existing.setVersion(0L);

            when(resourceRepo.findByRid("default.Customer.abc123"))
                    .thenReturn(Optional.of(existing));
            when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));
            when(resourceRepo.update(any(Resource.class))).thenAnswer(inv -> inv.getArgument(0));

            Resource updated = service.transitionState("default.Customer.abc123", "Active");
            assertThat(updated.getCurrentState()).isEqualTo("Active");
        }

        @Test
        @DisplayName("rejects illegal state transition")
        void rejectsIllegalTransition() {
            Resource existing = new Resource("default.Customer.abc123", "Customer", "agent", "Draft");
            existing.setVersion(0L);

            when(resourceRepo.findByRid("default.Customer.abc123"))
                    .thenReturn(Optional.of(existing));
            when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

            assertThatThrownBy(() -> service.transitionState("default.Customer.abc123", "Frozen"))
                    .isInstanceOf(ResourceValidationException.class)
                    .hasMessageContaining("Illegal state transition")
                    .hasMessageContaining("Draft")
                    .hasMessageContaining("Frozen");
        }
    }
}
