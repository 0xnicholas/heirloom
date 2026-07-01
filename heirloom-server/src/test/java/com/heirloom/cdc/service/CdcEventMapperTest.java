package com.heirloom.cdc.service;

import com.heirloom.cdc.domain.CdcSource;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.*;
import com.heirloom.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("CdcEventMapper")
@ExtendWith(MockitoExtension.class)
class CdcEventMapperTest {

    @Mock private ResourceService resourceService;
    @Mock private TypeRepository typeRepo;

    private CdcEventMapper mapper;
    private CdcSource source;

    @BeforeEach
    void setUp() {
        mapper = new CdcEventMapper(resourceService, typeRepo);

        ResourceType customerType = new ResourceType("Customer");
        customerType.setDomain("default");
        customerType.setFields(List.of(
                new Field("id", FieldType.NUMBER, true),
                new Field("name", FieldType.STRING, true),
                new Field("tier", FieldType.ENUM, false),
                new Field("status", FieldType.STRING, false)
        ));
        customerType.setAbilities(List.of(Ability.KEY, Ability.QUERY, Ability.MUTATE, Ability.DROP));
        customerType.setStateMachine(List.of(
                new StateTransition("Draft", "Active"),
                new StateTransition("Active", "Frozen")
        ));
        customerType.setInitialState("Draft");
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));

        source = new CdcSource();
        source.setName("test-source");
        source.setWatchedTables(Map.of(
                "customers", new CdcSource.TableConfig("Customer", "status", List.of("id"))
        ));
    }

    @Test
    @DisplayName("produces deterministic RID for same PK values")
    void deterministicRid() {
        String rid1 = mapper.buildDeterministicRid("Customer",
                Map.of("id", "42", "name", "Test"), List.of("id"));
        String rid2 = mapper.buildDeterministicRid("Customer",
                Map.of("id", "42", "name", "Changed"), List.of("id"));
        assertThat(rid1).isEqualTo(rid2);
        assertThat(rid1).startsWith("default.Customer.");
        assertThat(rid1).hasSize("default.Customer.".length() + 16); // 16 hex chars
    }

    @Test
    @DisplayName("different PK values produce different RIDs")
    void differentRids() {
        String rid1 = mapper.buildDeterministicRid("Customer", Map.of("id", "42"), List.of("id"));
        String rid2 = mapper.buildDeterministicRid("Customer", Map.of("id", "99"), List.of("id"));
        assertThat(rid1).isNotEqualTo(rid2);
    }

    @Test
    @DisplayName("INSERT creates resource with deterministic RID")
    void handlesInsert() {
        CdcEvent event = new CdcEvent("customers", "INSERT",
                Map.of("id", "1", "name", "Acme", "tier", "gold"),
                Map.of(), null, 0L);

        mapper.handleEvent(event, source);

        verify(resourceService).createWithRid(
                startsWith("default.Customer."), eq("Customer"), eq("cdc-system"),
                argThat(fields -> "Acme".equals(fields.get("name"))));
    }

    @Test
    @DisplayName("UPDATE triggers state transition when state column changes")
    void handlesUpdateWithStateChange() {
        CdcEvent event = new CdcEvent("customers", "UPDATE",
                Map.of("id", "1", "name", "Acme", "status", "Active"),
                Map.of("id", "1", "name", "Acme", "status", "Draft"),
                null, 0L);

        mapper.handleEvent(event, source);

        verify(resourceService).transitionState(contains("Customer"), eq("Active"));
        verify(resourceService).cdcUpdateFields(contains("Customer"), argThat(f -> !f.containsKey("status")));
    }

    @Test
    @DisplayName("DELETE marks resource as deleted")
    void handlesDelete() {
        CdcEvent event = new CdcEvent("customers", "DELETE",
                Map.of(), Map.of("id", "1", "name", "Acme"),
                null, 0L);

        mapper.handleEvent(event, source);

        verify(resourceService).markDeleted(startsWith("default.Customer."));
    }
}
