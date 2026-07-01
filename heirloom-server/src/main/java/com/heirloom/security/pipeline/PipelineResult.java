package com.heirloom.security.pipeline;

import java.util.List;
import java.util.Map;

/**
 * Result of an Action pipeline execution — success or denial.
 */
public record PipelineResult(
        String status,
        String action,
        String actorType,
        String actorId,
        String actorRole,
        String targetResource,
        Map<String, Object> result,
        long durationMs,
        List<StepResult> steps,
        int deniedAtStep,
        String deniedAtName,
        String deniedReason
) {
    public record StepResult(int step, String name, String status, long durationMs) {}
}
