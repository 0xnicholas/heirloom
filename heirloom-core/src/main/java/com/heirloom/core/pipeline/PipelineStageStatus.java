package com.heirloom.core.pipeline;

import java.time.Instant;
import java.util.UUID;

public interface PipelineStageStatus {
    UUID getRunUuid();
    String getStageName();
    PipelineStatus getStatus();
    int getAttempts();
    int getMaxAttempts();
    Instant getStartedAt();
    Instant getCompletedAt();
    Instant getNextRetryAt();
    String getLastError();
}