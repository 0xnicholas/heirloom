package com.heirloom.security.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.domain.Resource;
import com.heirloom.repository.RoleRepository;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.Ability;
import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.guard.StateMachineGuard;
import com.heirloom.security.domain.Action;
import com.heirloom.security.domain.StateGate;
import com.heirloom.service.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nine-step Action execution pipeline (ADR-005).
 * <p>
 * Auth → Role → Capability → Gate → State → Validate → Execute → Event → Notify
 */
@Component
public class ActionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ActionPipeline.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TypeSafeCapabilityResolver capabilityResolver;
    private final ResourceService resourceService;
    private final RoleRepository roleRepo;
    private final TypeRepository typeRepo;

    public ActionPipeline(TypeSafeCapabilityResolver capabilityResolver,
                          ResourceService resourceService,
                          RoleRepository roleRepo,
                          TypeRepository typeRepo) {
        this.capabilityResolver = capabilityResolver;
        this.resourceService = resourceService;
        this.roleRepo = roleRepo;
        this.typeRepo = typeRepo;
    }

    @Transactional
    public PipelineResult execute(Action action, String actorType, String actorId,
                                   String actorRole, String targetResourceRid,
                                   Map<String, Object> params) {
        long pipelineStart = System.currentTimeMillis();
        PipelineContext ctx = new PipelineContext(action, actorType, actorId, actorRole,
                targetResourceRid, params);

        try {
            stepAuth(ctx);       // 1
            stepRole(ctx);       // 2
            stepCapability(ctx); // 3 — resolve and cache
            stepGate(ctx);       // 4 — use cached capabilities
            stepState(ctx);      // 5
            stepValidate(ctx);   // 6
            stepExecute(ctx);    // 7
            stepEvent(ctx, pipelineStart);  // 8
            stepNotify(ctx);     // 9
            return ctx.buildSuccessResult();

        } catch (PipelineRejection e) {
            ctx.markDenied(e.getStep(), e.getStepName(), e.getMessage());
            log.warn("Pipeline denied at step {} ({}) for action {}: {}",
                    e.getStep(), e.getStepName(), action.getName(), e.getMessage());
            // Record denial event even after rejection
            ctx.addStepResult("EVENT", "PASSED", 0L);
            return ctx.buildDeniedResult();
        }
    }

    // --- Steps ---

    private void stepAuth(PipelineContext ctx) {
        long start = System.currentTimeMillis();
        if (ctx.getActorId() == null || ctx.getActorId().isBlank()) {
            throw new PipelineRejection(1, "AUTH", "Missing X-Actor-Id header");
        }
        if (ctx.getActorType() == null || ctx.getActorType().isBlank()) {
            throw new PipelineRejection(1, "AUTH", "Missing X-Actor-Type header");
        }
        ctx.addStepResult("AUTH", "PASSED", System.currentTimeMillis() - start);
    }

    private void stepRole(PipelineContext ctx) {
        long start = System.currentTimeMillis();
        String roleName = ctx.getActorRole();
        if (roleName == null || roleName.isBlank()) {
            throw new PipelineRejection(2, "ROLE", "No role assigned to actor '" + ctx.getActorId() + "'");
        }
        var roles = roleRepo.findByName(roleName);
        if (roles.isEmpty()) {
            throw new PipelineRejection(2, "ROLE", "Role '" + roleName + "' not found");
        }
        ctx.setEffectiveRoles(List.of(roles.get()));
        ctx.addStepResult("ROLE", "PASSED", System.currentTimeMillis() - start);
    }

    private void stepCapability(PipelineContext ctx) {
        long start = System.currentTimeMillis();
        Ability requiredAbility = ctx.getAction().resolveRequiredAbility();
        String targetTypeFqn = ctx.getAction().resolveTargetType();

        List<CapabilityRecord> caps = capabilityResolver.resolve(
                ctx.getActorRole(), requiredAbility, targetTypeFqn);
        ctx.setCapabilities(caps);  // Cache for Gate step
        ctx.addStepResult("CAPABILITY", "PASSED", System.currentTimeMillis() - start);
    }

    private void stepGate(PipelineContext ctx) {
        long start = System.currentTimeMillis();
        Ability requiredAbility = ctx.getAction().resolveRequiredAbility();
        if (requiredAbility == null) {
            ctx.addStepResult("GATE", "PASSED", System.currentTimeMillis() - start);
            return;
        }

        List<CapabilityRecord> caps = ctx.getCapabilities();
        if (caps == null || caps.isEmpty()) {
            throw new PipelineRejection(4, "GATE",
                    "Actor '" + ctx.getActorId() + "' lacks capability '"
                    + requiredAbility + "' on type '" + ctx.getAction().resolveTargetType() + "'");
        }
        ctx.addStepResult("GATE", "PASSED", System.currentTimeMillis() - start);
    }

    private void stepState(PipelineContext ctx) {
        long start = System.currentTimeMillis();
        Resource resource = resourceService.getByRid(ctx.getTargetResourceRid());
        ctx.setTargetResource(resource);

        ResourceType type = typeRepo.findByName(resource.getResourceType())
                .orElseThrow(() -> new PipelineRejection(5, "STATE",
                        "ResourceType '" + resource.getResourceType() + "' not found"));
        ctx.setTargetType(type);

        String current = resource.getCurrentState();
        if (current == null) {
            current = type.getInitialState();
        }
        ctx.setCurrentState(current);

        StateGate gate = ctx.getAction().resolveStateGate();
        if (gate != null) {
            try {
                StateMachineGuard.guardGate(type, current, gate);
            } catch (com.heirloom.schema.guard.StateGuardException e) {
                throw new PipelineRejection(5, "STATE", e.getMessage());
            }
        }

        ctx.addStepResult("STATE", "PASSED", System.currentTimeMillis() - start);
    }

    @SuppressWarnings("unchecked")
    private void stepValidate(PipelineContext ctx) {
        long start = System.currentTimeMillis();
        String rules = ctx.getAction().getValidationRules();
        if (rules == null || rules.equals("{}")) {
            ctx.addStepResult("VALIDATE", "PASSED", System.currentTimeMillis() - start);
            return;
        }
        // Simple SpEL validation — placeholder for I-3 scope
        log.debug("Validation rules present but SpEL eval not implemented: {}", rules);
        ctx.addStepResult("VALIDATE", "PASSED", System.currentTimeMillis() - start);
    }

    @SuppressWarnings("unchecked")
    private void stepExecute(PipelineContext ctx) {
        long start = System.currentTimeMillis();
        Action action = ctx.getAction();
        Resource resource = ctx.getTargetResource();
        StateGate gate = action.resolveStateGate();
        Map<String, Object> params = ctx.getInputParams();

        // Parse executeParams template
        String executeParamsJson = action.getExecuteParams();
        Map<String, Object> template;
        try {
            template = parseExecuteParams(executeParamsJson);
        } catch (Exception e) {
            throw new PipelineRejection(7, "EXECUTE",
                    "Failed to parse executeParams: " + e.getMessage());
        }

        Map<String, Object> updates = (Map<String, Object>) template.getOrDefault("updates", Map.of());
        Map<String, Object> fieldUpdates = (Map<String, Object>) updates.getOrDefault("fields", Map.of());
        String templateNewState = (String) updates.get("newState");

        // Resolve template variables
        Map<String, Object> resolvedFields = resolveTemplate(fieldUpdates, params, ctx.getActorId());

        // Determine target state
        String targetState = null;
        if (gate != null && gate.toState() != null) {
            targetState = gate.toState();
            if (templateNewState != null && !templateNewState.equals(gate.toState())) {
                throw new PipelineRejection(7, "EXECUTE",
                        "Template newState '" + templateNewState
                        + "' conflicts with gate.toState '" + gate.toState() + "'");
            }
        } else if (templateNewState != null) {
            targetState = templateNewState;
        }

        // Execute updates
        if (!resolvedFields.isEmpty()) {
            resourceService.updateFields(resource.getRid(), resolvedFields, resource.getVersion());
        }
        if (targetState != null) {
            resourceService.transitionState(resource.getRid(), targetState);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updatedFields", resolvedFields.keySet());
        if (targetState != null) result.put("newState", targetState);
        ctx.setExecutionResult(result);
        ctx.addStepResult("EXECUTE", "PASSED", System.currentTimeMillis() - start);
    }

    private void stepEvent(PipelineContext ctx, long pipelineStart) {
        long start = System.currentTimeMillis();
        long elapsed = System.currentTimeMillis() - pipelineStart;
        log.info("ACTION_INVOKED: action={} actor={} resource={} duration={}ms",
                ctx.getAction().getName(), ctx.getActorId(),
                ctx.getTargetResourceRid(), elapsed);
        ctx.addStepResult("EVENT", "PASSED", System.currentTimeMillis() - start);
    }

    private void stepNotify(PipelineContext ctx) {
        long start = System.currentTimeMillis();
        // Spring ApplicationEvent placeholder — future Automation integration
        ctx.addStepResult("NOTIFY", "PASSED", System.currentTimeMillis() - start);
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveTemplate(Map<String, Object> template,
                                                  Map<String, Object> params, String actorId) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.startsWith("{{") && s.endsWith("}}")) {
                String key = s.substring(2, s.length() - 2).trim();
                if (key.startsWith("params.")) {
                    resolved.put(entry.getKey(), params.get(key.substring("params.".length())));
                } else if ("actor.id".equals(key)) {
                    resolved.put(entry.getKey(), actorId);
                } else {
                    resolved.put(entry.getKey(), params.get(key));
                }
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseExecuteParams(String json) {
        if (json == null || json.isBlank() || json.equals("{}")) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new PipelineRejection(7, "EXECUTE",
                    "Failed to parse executeParams JSON: " + e.getMessage());
        }
    }
}
