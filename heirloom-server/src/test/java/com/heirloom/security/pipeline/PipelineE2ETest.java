package com.heirloom.security.pipeline;

import com.heirloom.domain.Resource;
import com.heirloom.repository.*;
import com.heirloom.schema.domain.*;
import com.heirloom.security.RoleCapabilityCache;
import com.heirloom.security.domain.Action;
import com.heirloom.security.domain.Role;
import com.heirloom.security.domain.StateGate;
import com.heirloom.security.validation.ActionValidator;
import com.heirloom.service.ResourceService;
import com.heirloom.service.ResourceValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the core loop using real service/pipeline instances
 * with mocked repositories. Validates:
 * <p>
 * ResourceType → Resource → Action → Pipeline → State change
 */
@DisplayName("Pipeline E2E")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineE2ETest {

    @Mock private ResourceRepository resourceRepo;
    @Mock private ResourceJpaRepository resourceJpa;
    @Mock private TypeRepository typeRepo;
    @Mock private ActionRepository actionRepo;
    @Mock private ActionJpaRepository actionJpa;
    @Mock private RoleRepository roleRepo;
    @Mock private RoleCapabilityCache capabilityCache;
    @Mock private com.heirloom.graph.GraphStoreService graphStore;
    @Mock private com.heirloom.security.condition.ConditionEvaluator conditionEvaluator;

    private ResourceService resourceService;
    private ActionPipeline pipeline;
    private TypeSafeCapabilityResolver capabilityResolver;
    private ResourceType customerType;

    @BeforeEach
    void setUp() {
        // Wire real services with mocked repos
        resourceService = new ResourceService(resourceRepo, typeRepo, graphStore);
        capabilityResolver = new TypeSafeCapabilityResolver(capabilityCache);
        pipeline = new ActionPipeline(capabilityResolver, resourceService, roleRepo, typeRepo, conditionEvaluator);

        // ResourceType fixture
        customerType = new ResourceType("Customer");
        customerType.setDomain("default");
        customerType.setFields(List.of(
                new Field("name", FieldType.STRING, true),
                new Field("tier", FieldType.ENUM, false, List.of("free", "pro", "enterprise")),
                new Field("reviewedBy", FieldType.STRING, false)
        ));
        customerType.setAbilities(List.of(Ability.KEY, Ability.QUERY, Ability.MUTATE));
        customerType.setStateMachine(List.of(
                new StateTransition("Draft", "Active"),
                new StateTransition("Active", "Frozen"),
                new StateTransition("Frozen", "Active", "unfreeze")
        ));
        customerType.setInitialState("Draft");
    }

    @Test
    @DisplayName("full pipeline: create → transition → execute action → verify state + fields")
    void fullPipelineExecution() {
        // --- Create Resource ---
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));
        when(resourceRepo.create(any(Resource.class))).thenAnswer(inv -> inv.getArgument(0));

        Resource cust = resourceService.create("Customer", "agent-123",
                Map.of("name", "Acme Corp", "tier", "free"));
        String rid = cust.getRid();
        assertThat(cust.getCurrentState()).isEqualTo("Draft");

        // --- Transition to Active ---
        Resource draft = new Resource(rid, "Customer", "agent-123", "Draft");
        draft.setFields(Map.of("name", "Acme Corp", "tier", "free"));
        draft.setVersion(0L);
        when(resourceRepo.findByRid(rid)).thenReturn(Optional.of(draft));
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));
        when(resourceRepo.update(any(Resource.class))).thenAnswer(inv -> inv.getArgument(0));

        Resource active = resourceService.transitionState(rid, "Active");
        assertThat(active.getCurrentState()).isEqualTo("Active");

        // --- Set up Action ---
        Action action = new Action();
        action.setName("approve_customer");
        action.setTargetType("Customer");
        action.setTargetTypeFqn("default.Customer");
        action.setRequiredAbilityEnum("MUTATE");
        action.setStateGateJson(new StateGate("Active", "Frozen"));
        action.setExecuteParams(
                "{\"updates\":{\"fields\":{\"tier\":\"{{params.newTier}}\",\"reviewedBy\":\"{{actor.id}}\"},\"newState\":\"Frozen\"}}");

        // --- Set up Role/Capability ---
        Role role = new Role();
        role.setName("compliance-agent");
        List<Map<String, String>> caps = List.of(
                Map.of("ability", "MUTATE", "resourceType", "default.Customer"));
        when(capabilityCache.get("compliance-agent")).thenReturn(caps);
        when(roleRepo.findByName("compliance-agent")).thenReturn(Optional.of(role));

        // --- Mock Resource reads for pipeline ---
        Resource activeResource = new Resource(rid, "Customer", "agent-123", "Active");
        activeResource.setFields(new LinkedHashMap<>(Map.of("name", "Acme Corp", "tier", "free")));
        activeResource.setVersion(0L);
        when(resourceRepo.findByRid(rid)).thenReturn(Optional.of(activeResource));

        // --- Execute Pipeline ---
        PipelineResult result = pipeline.execute(
                action, "agent", "agent-123", "compliance-agent",
                rid, Map.of("newTier", "enterprise"));

        // --- Verify ---
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.steps()).hasSize(9);
        assertThat(result.steps().stream().allMatch(s -> "PASSED".equals(s.status()))).isTrue();
    }

    @Test
    @DisplayName("pipeline denies when capability is missing")
    void deniesWhenCapabilityMissing() {
        Resource cust = createDraftResource();
        transitionToActive(cust.getRid());

        Action action = buildAction("drop_customer", "DROP", "Active", "Frozen");

        // Role has MUTATE, not DROP
        List<Map<String, String>> caps = List.of(
                Map.of("ability", "MUTATE", "resourceType", "default.Customer"));
        when(capabilityCache.get("compliance-agent")).thenReturn(caps);
        when(roleRepo.findByName("compliance-agent")).thenReturn(Optional.of(new Role()));

        mockActiveResource(cust.getRid());

        PipelineResult result = pipeline.execute(
                action, "agent", "agent-456", "compliance-agent",
                cust.getRid(), Map.of());

        assertThat(result.status()).isEqualTo("DENIED");
        assertThat(result.deniedAtName()).isEqualTo("GATE");
    }

    @Test
    @DisplayName("pipeline denies when state mismatches gate")
    void deniesWhenStateMismatch() {
        Resource cust = createDraftResource();
        // Resource stays in Draft — don't transition to Active

        Action action = buildAction("approve_customer", "MUTATE", "Active", "Frozen");

        List<Map<String, String>> caps = List.of(
                Map.of("ability", "MUTATE", "resourceType", "default.Customer"));
        when(capabilityCache.get("compliance-agent")).thenReturn(caps);
        when(roleRepo.findByName("compliance-agent")).thenReturn(Optional.of(new Role()));

        // Resource is in Draft state
        Resource draftResource = new Resource(cust.getRid(), "Customer", "agent-789", "Draft");
        draftResource.setFields(new LinkedHashMap<>(Map.of("name", "Gamma", "tier", "free")));
        draftResource.setVersion(0L);
        when(resourceRepo.findByRid(cust.getRid())).thenReturn(Optional.of(draftResource));

        PipelineResult result = pipeline.execute(
                action, "agent", "agent-789", "compliance-agent",
                cust.getRid(), Map.of());

        assertThat(result.status()).isEqualTo("DENIED");
        assertThat(result.deniedAtName()).isEqualTo("STATE");
    }

    // --- helpers ---

    private Resource createDraftResource() {
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));
        when(resourceRepo.create(any(Resource.class))).thenAnswer(inv -> inv.getArgument(0));
        return resourceService.create("Customer", "agent-test", Map.of("name", "Test", "tier", "free"));
    }

    private void transitionToActive(String rid) {
        Resource draft = new Resource(rid, "Customer", "agent-test", "Draft");
        draft.setFields(Map.of("name", "Test", "tier", "free"));
        draft.setVersion(0L);
        when(resourceRepo.findByRid(rid)).thenReturn(Optional.of(draft));
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));
        when(resourceRepo.update(any(Resource.class))).thenAnswer(inv -> inv.getArgument(0));
        resourceService.transitionState(rid, "Active");
    }

    private void mockActiveResource(String rid) {
        Resource active = new Resource(rid, "Customer", "agent-test", "Active");
        active.setFields(new LinkedHashMap<>(Map.of("name", "Test", "tier", "free")));
        active.setVersion(0L);
        when(resourceRepo.findByRid(rid)).thenReturn(Optional.of(active));
        when(typeRepo.findByName("Customer")).thenReturn(Optional.of(customerType));
        when(resourceRepo.update(any(Resource.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Action buildAction(String name, String ability, String fromState, String toState) {
        Action action = new Action();
        action.setName(name);
        action.setTargetType("Customer");
        action.setTargetTypeFqn("default.Customer");
        action.setRequiredAbilityEnum(ability);
        action.setStateGateJson(new StateGate(fromState, toState));
        action.setExecuteParams("{}");
        return action;
    }
}
