package com.heirloom.pipeline.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineResultJpaRepository extends JpaRepository<PipelineResultEntity, Long> {
    List<PipelineResultEntity> findByRunUuid(UUID runUuid);
    Optional<PipelineResultEntity> findByRunUuidAndStageName(UUID runUuid, String stageName);
    void deleteByRunUuidAndStageName(UUID runUuid, String stageName);
}
