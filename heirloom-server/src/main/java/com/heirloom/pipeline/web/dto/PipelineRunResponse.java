package com.heirloom.pipeline.web.dto;

import com.heirloom.core.pipeline.PipelineStatus;
import com.heirloom.core.pipeline.PipelineTriggerType;
import com.heirloom.pipeline.persistence.PipelineRunEntity;
import com.heirloom.pipeline.persistence.PipelineStageStatusJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PipelineRunResponse(
    UUID runUuid,
    String tenantId,
    String sourceFqn,
    PipelineStatus status,
    PipelineTriggerType triggerType,
    String correlationId,
    List<PipelineStageStatusDto> stages,
    Instant createdAt,
    Instant completedAt
) {
    public static PipelineRunResponse from(PipelineRunEntity run,
                                            PipelineStageStatusJpaRepository stageRepo) {
        var stages = stageRepo.findByRunUuid(run.getRunUuid()).stream()
            .map(PipelineStageStatusDto::from)
            .toList();
        return new PipelineRunResponse(
            run.getRunUuid(), run.getTenantId(), run.getSourceFqn(),
            run.getStatus(), run.getTriggerType(), run.getCorrelationId().toString(),
            stages, run.getCreatedAt(), run.getCompletedAt());
    }
}