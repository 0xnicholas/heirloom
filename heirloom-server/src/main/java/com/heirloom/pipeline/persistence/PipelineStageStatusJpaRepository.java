package com.heirloom.pipeline.persistence;

import com.heirloom.core.pipeline.PipelineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineStageStatusJpaRepository
        extends JpaRepository<PipelineStageStatusEntity, Long> {
    List<PipelineStageStatusEntity> findByRunUuid(UUID runUuid);
    Optional<PipelineStageStatusEntity> findByRunUuidAndStageName(UUID runUuid, String stageName);
    List<PipelineStageStatusEntity> findByStatusAndNextRetryAtBefore(
        PipelineStatus status, Instant before);
}
