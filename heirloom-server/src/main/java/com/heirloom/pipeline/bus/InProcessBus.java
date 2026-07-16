package com.heirloom.pipeline.bus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.PipelineEvent;
import com.heirloom.core.pipeline.PipelineEventBus;
import com.heirloom.core.pipeline.PipelineStatus;
import com.heirloom.pipeline.persistence.PipelineOutboxEntity;
import com.heirloom.pipeline.persistence.PipelineOutboxJpaRepository;
import com.heirloom.pipeline.persistence.PipelineRunJpaRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

@Component
public class InProcessBus implements PipelineEventBus {

    private final PipelineOutboxJpaRepository outboxRepo;
    private final PipelineRunJpaRepository runRepo;
    private final ObjectMapper mapper;

    public InProcessBus(PipelineOutboxJpaRepository outboxRepo,
                         PipelineRunJpaRepository runRepo,
                         ObjectMapper mapper) {
        this.outboxRepo = outboxRepo;
        this.runRepo = runRepo;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void publish(PipelineEvent event) {
        var run = runRepo.findByRunUuid(event.runUuid())
            .orElseThrow(() -> new IllegalStateException(
                "PipelineRun not found: " + event.runUuid()));

        if (!List.of(PipelineStatus.PENDING, PipelineStatus.RUNNING, PipelineStatus.RETRYING)
                .contains(run.getStatus())) {
            throw new IllegalStateException(
                "PipelineRun " + run.getRunUuid() + " in terminal status: " + run.getStatus());
        }

        run.setStatus(PipelineStatus.RUNNING);
        run.setUpdatedAt(Instant.now());
        runRepo.save(run);

        var entity = new PipelineOutboxEntity();
        entity.setEventId(event.eventId());
        entity.setRunUuid(event.runUuid());
        entity.setEventType(event.type().name());
        entity.setPayload(serialize(event));
        outboxRepo.save(entity);
    }

    @Override
    @Transactional
    public void start() {
        outboxRepo.releaseExpiredClaims();
    }

    private String serialize(PipelineEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }
}