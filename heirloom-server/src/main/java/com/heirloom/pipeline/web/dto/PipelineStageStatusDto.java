package com.heirloom.pipeline.web.dto;

import com.heirloom.core.pipeline.PipelineStatus;
import com.heirloom.pipeline.persistence.PipelineStageStatusEntity;
import java.time.Instant;

public record PipelineStageStatusDto(
    String stageName,
    PipelineStatus status,
    int attempts,
    Instant startedAt,
    Instant completedAt
) {
    public static PipelineStageStatusDto from(PipelineStageStatusEntity e) {
        return new PipelineStageStatusDto(
            e.getStageName(), e.getStatus(), e.getAttempts(),
            e.getStartedAt(), e.getCompletedAt());
    }
}