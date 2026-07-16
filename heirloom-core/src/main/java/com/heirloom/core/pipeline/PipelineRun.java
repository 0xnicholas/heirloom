package com.heirloom.core.pipeline;

import java.time.Instant;
import java.util.UUID;

public interface PipelineRun {
    UUID getRunUuid();
    String getTenantId();
    String getSourceFqn();
    PipelineStatus getStatus();
    String getCorrelationId();
    PipelineTriggerType getTriggerType();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    Instant getCompletedAt();
}