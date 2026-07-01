package com.heirloom.security.pipeline;

import com.heirloom.domain.Resource;
import com.heirloom.schema.domain.ResourceType;
import com.heirloom.security.domain.Action;
import com.heirloom.security.domain.Role;
import com.heirloom.security.pipeline.CapabilityRecord;

import java.util.*;

/**
 * Mutable context carried through the Action pipeline.
 * Steps fill intermediate state as they execute.
 */
public class PipelineContext {

    private final Action action;
    private final String actorType;
    private final String actorId;
    private final String actorRole;
    private final String targetResourceRid;
    private final Map<String, Object> inputParams;

    // Filled by steps
    private List<Role> effectiveRoles;
    private List<CapabilityRecord> capabilities;
    private ResourceType targetType;
    private Resource targetResource;
    private String currentState;
    private Map<String, Object> executionResult;

    private final long startedAt = System.currentTimeMillis();
    private final List<PipelineResult.StepResult> stepResults = new ArrayList<>();
    private boolean denied = false;
    private int deniedAtStep;
    private String deniedAtName;
    private String deniedReason;

    public PipelineContext(Action action, String actorType, String actorId, String actorRole,
                           String targetResourceRid, Map<String, Object> inputParams) {
        this.action = action;
        this.actorType = actorType;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.targetResourceRid = targetResourceRid;
        this.inputParams = inputParams != null ? inputParams : Map.of();
    }

    public Action getAction() { return action; }
    public String getActorType() { return actorType; }
    public String getActorId() { return actorId; }
    public String getActorRole() { return actorRole; }
    public String getTargetResourceRid() { return targetResourceRid; }
    public Map<String, Object> getInputParams() { return inputParams; }

    public List<Role> getEffectiveRoles() { return effectiveRoles; }
    public void setEffectiveRoles(List<Role> r) { this.effectiveRoles = r; }

    public List<CapabilityRecord> getCapabilities() { return capabilities; }
    public void setCapabilities(List<CapabilityRecord> c) { this.capabilities = c; }

    public ResourceType getTargetType() { return targetType; }
    public void setTargetType(ResourceType t) { this.targetType = t; }

    public Resource getTargetResource() { return targetResource; }
    public void setTargetResource(Resource r) { this.targetResource = r; }

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String s) { this.currentState = s; }

    public Map<String, Object> getExecutionResult() { return executionResult; }
    public void setExecutionResult(Map<String, Object> r) { this.executionResult = r; }

    public void addStepResult(String name, String status, long durationMs) {
        stepResults.add(new PipelineResult.StepResult(stepResults.size() + 1, name, status, durationMs));
    }

    public void markDenied(int step, String name, String reason) {
        this.denied = true;
        this.deniedAtStep = step;
        this.deniedAtName = name;
        this.deniedReason = reason;
    }

    public PipelineResult buildSuccessResult() {
        return new PipelineResult("SUCCESS", action.getName(), actorType, actorId, actorRole,
                targetResourceRid, executionResult, System.currentTimeMillis() - startedAt,
                stepResults, -1, null, null);
    }

    public PipelineResult buildDeniedResult() {
        return new PipelineResult("DENIED", action.getName(), actorType, actorId, actorRole,
                targetResourceRid, null, System.currentTimeMillis() - startedAt,
                stepResults, deniedAtStep, deniedAtName, deniedReason);
    }
}
