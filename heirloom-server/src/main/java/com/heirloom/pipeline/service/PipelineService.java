package com.heirloom.pipeline.service;

import com.heirloom.core.pipeline.PipelineStatus;
import com.heirloom.core.pipeline.PipelineTriggerType;
import com.heirloom.pipeline.bus.PipelineEventPublisher;
import com.heirloom.pipeline.persistence.PipelineRunEntity;
import com.heirloom.pipeline.persistence.PipelineRunJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PipelineService {

    private final PipelineRunJpaRepository runRepo;
    private final PipelineEventPublisher publisher;

    public PipelineService(PipelineRunJpaRepository runRepo, PipelineEventPublisher publisher) {
        this.runRepo = runRepo;
        this.publisher = publisher;
    }

    @Transactional
    public PipelineRunEntity startRun(String tenantId, String sourceFqn,
                                       List<String> tableFqns, PipelineTriggerType triggerType) {
        var existing = runRepo.findByTenantIdAndSourceFqn(tenantId, sourceFqn).stream()
            .filter(r -> List.of(PipelineStatus.PENDING, PipelineStatus.RUNNING, PipelineStatus.RETRYING)
                .contains(r.getStatus()))
            .findFirst();
        if (existing.isPresent()) {
            throw new IllegalStateException(
                "active run already exists for " + sourceFqn + ": " + existing.get().getRunUuid());
        }

        UUID runUuid = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        var run = new PipelineRunEntity();
        run.setRunUuid(runUuid);
        run.setTenantId(tenantId);
        run.setSourceFqn(sourceFqn);
        run.setStatus(PipelineStatus.PENDING);
        run.setCorrelationId(correlationId);
        run.setTriggerType(triggerType);
        run.setTableFqns(String.join(",", tableFqns));
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());

        try {
            runRepo.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("active run exists (race): " + sourceFqn, e);
        }

        publisher.publishIngestionRequested(runUuid, tenantId, sourceFqn,
            tableFqns, correlationId.toString());

        return run;
    }

    public PipelineRunEntity get(UUID runUuid) {
        return runRepo.findByRunUuid(runUuid).orElseThrow(
            () -> new IllegalArgumentException("run not found: " + runUuid));
    }

    public List<PipelineRunEntity> list(String sourceFqn, PipelineStatus status, int limit, int offset) {
        if (sourceFqn != null && status != null) {
            return runRepo.findByTenantIdAndSourceFqn("default", sourceFqn).stream()
                .filter(r -> r.getStatus() == status)
                .skip(offset).limit(limit).toList();
        }
        if (sourceFqn != null) {
            return runRepo.findByTenantIdAndSourceFqn("default", sourceFqn).stream()
                .skip(offset).limit(limit).toList();
        }
        if (status != null) {
            return runRepo.findByStatusIn(List.of(status)).stream()
                .skip(offset).limit(limit).toList();
        }
        return runRepo.findAll().stream().skip(offset).limit(limit).toList();
    }
}