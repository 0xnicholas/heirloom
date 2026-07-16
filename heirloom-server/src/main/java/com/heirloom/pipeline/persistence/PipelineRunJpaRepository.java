package com.heirloom.pipeline.persistence;

import com.heirloom.core.pipeline.PipelineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineRunJpaRepository extends JpaRepository<PipelineRunEntity, Long> {
    Optional<PipelineRunEntity> findByRunUuid(UUID runUuid);
    List<PipelineRunEntity> findByStatusIn(List<PipelineStatus> statuses);
    List<PipelineRunEntity> findByTenantIdAndSourceFqn(String tenantId, String sourceFqn);
}
