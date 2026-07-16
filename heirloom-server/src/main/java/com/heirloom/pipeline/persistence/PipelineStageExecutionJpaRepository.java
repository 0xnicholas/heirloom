package com.heirloom.pipeline.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PipelineStageExecutionJpaRepository
        extends JpaRepository<PipelineStageExecutionEntity, PipelineStageExecutionEntity.PK> {
    boolean existsByInputEventIdAndStageNameAndStatus(
        UUID inputEventId, String stageName, String status);
}
