package com.heirloom.pipeline.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.*;
import com.heirloom.pipeline.persistence.*;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final String INSTANCE_ID = ManagementFactory.getRuntimeMXBean().getName();

    private final PipelineOutboxJpaRepository outboxRepo;
    private final PipelineRunJpaRepository runRepo;
    private final PipelineStageStatusJpaRepository stageRepo;
    private final PipelineResultJpaRepository resultRepo;
    private final PipelineStageExecutionJpaRepository execRepo;
    private final DeadLetterJpaRepository dlqRepo;
    private final PipelineStageRegistry registry;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Value("${heirloom.pipeline.batch-size:50}")
    private int batchSize;

    public OutboxProcessor(PipelineOutboxJpaRepository outboxRepo,
                            PipelineRunJpaRepository runRepo,
                            PipelineStageStatusJpaRepository stageRepo,
                            PipelineResultJpaRepository resultRepo,
                            PipelineStageExecutionJpaRepository execRepo,
                            DeadLetterJpaRepository dlqRepo,
                            PipelineStageRegistry registry,
                            ObjectMapper mapper,
                            Clock clock) {
        this.outboxRepo = outboxRepo;
        this.runRepo = runRepo;
        this.stageRepo = stageRepo;
        this.resultRepo = resultRepo;
        this.execRepo = execRepo;
        this.dlqRepo = dlqRepo;
        this.registry = registry;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${heirloom.pipeline.outbox-poll-seconds:5s}")
    @Transactional
    public void dispatchPending() {
        Instant leaseUntil = clock.instant().plus(Duration.ofSeconds(60));
        List<Object[]> claimed = outboxRepo.claimBatch(INSTANCE_ID, leaseUntil, batchSize);
        if (claimed.isEmpty()) return;

        for (Object[] row : claimed) {
            Long id = (Long) row[0];
            UUID eventId = (UUID) row[1];
            String eventType = (String) row[2];
            String payload = (String) row[3];
            try {
                dispatchOne(id, eventId, eventType, payload);
            } catch (Exception e) {
                log.error("OutboxProcessor dispatch failed for id={} eventId={}", id, eventId, e);
            }
        }
    }

    private void dispatchOne(Long outboxId, UUID eventId, String eventType, String payload) {
        PipelineEvent event = deserialize(eventType, payload);

        var stageOpt = registry.find(event.type());
        String stageName = inferStageName(event.type());
        if (stageOpt.isEmpty()) {
            handleFatal(outboxId, event, stageName, "no stage registered for " + eventType);
            return;
        }
        var stage = stageOpt.get();

        if (execRepo.existsByInputEventIdAndStageNameAndStatus(
                eventId, stageName, "COMPLETED")) {
            markDispatched(outboxId);
            return;
        }

        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow();
        run.setStatus(PipelineStatus.RUNNING);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);

        var stageEntity = stageRepo.findByRunUuidAndStageName(event.runUuid(), stageName)
            .orElseGet(() -> {
                var s = new PipelineStageStatusEntity();
                s.setRunUuid(event.runUuid());
                s.setStageName(stageName);
                s.setStatus(PipelineStatus.PENDING);
                s.setAttempts(0);
                s.setMaxAttempts(3);
                return s;
            });
        stageEntity.setStatus(PipelineStatus.RUNNING);
        stageEntity.setStartedAt(clock.instant());
        stageRepo.save(stageEntity);

        var ctx = new PipelineContext(event.runUuid(), event.tenantId(), event.sourceFqn(),
            event.correlationId(), stageName, stageEntity.getAttempts() + 1,
            clock.instant(), clock);

        try {
            PipelineEvent nextEvent = stage.apply(event, ctx);
            stageEntity.setStatus(PipelineStatus.COMPLETED);
            stageEntity.setCompletedAt(clock.instant());
            stageRepo.save(stageEntity);

            UUID outputEventId = nextEvent != null ? nextEvent.eventId() : null;
            execRepo.save(new PipelineStageExecutionEntity(
                eventId, stageName, "COMPLETED", outputEventId));

            if (nextEvent != null) {
                var nextEntity = new PipelineOutboxEntity();
                nextEntity.setEventId(nextEvent.eventId());
                nextEntity.setRunUuid(nextEvent.runUuid());
                nextEntity.setEventType(nextEvent.type().name());
                nextEntity.setPayload(serialize(nextEvent));
                outboxRepo.save(nextEntity);
            } else {
                var stages = stageRepo.findByRunUuid(event.runUuid());
                boolean allDone = stages.stream()
                    .allMatch(s -> s.getStatus() == PipelineStatus.COMPLETED);
                if (allDone) {
                    run.setStatus(PipelineStatus.COMPLETED);
                    run.setCompletedAt(clock.instant());
                    run.setUpdatedAt(clock.instant());
                    runRepo.save(run);
                }
            }

            markDispatched(outboxId);
        } catch (RecoverableFailure rf) {
            handleRecoverable(outboxId, event, stageName, stageEntity, rf);
        } catch (FatalFailure ff) {
            handleFatal(outboxId, event, stageName, ff.getMessage());
        } catch (PipelineFailure pf) {
            handleFatal(outboxId, event, stageName, pf.getMessage());
        }
    }

    private void markDispatched(Long outboxId) {
        outboxRepo.findById(outboxId).ifPresent(e -> {
            e.setStatus("DISPATCHED");
            e.setDispatchedAt(clock.instant());
            outboxRepo.save(e);
        });
    }

    private void handleRecoverable(Long outboxId, PipelineEvent event, String stageName,
                                    PipelineStageStatusEntity stageEntity,
                                    RecoverableFailure rf) {
        int newAttempts = stageEntity.getAttempts() + 1;
        if (newAttempts >= stageEntity.getMaxAttempts()) {
            handleFatal(outboxId, event, stageName, "max attempts reached: " + rf.getMessage());
            return;
        }
        long backoffSec = Math.min((long) Math.pow(2, newAttempts) * 10, 300);
        stageEntity.setStatus(PipelineStatus.RETRYING);
        stageEntity.setAttempts(newAttempts);
        stageEntity.setNextRetryAt(clock.instant().plusSeconds(backoffSec));
        stageEntity.setLastError(rf.getMessage());
        stageRepo.save(stageEntity);

        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow();
        run.setStatus(PipelineStatus.RETRYING);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);

        var retryEntity = new PipelineOutboxEntity();
        retryEntity.setEventId(UUID.randomUUID());
        retryEntity.setRunUuid(event.runUuid());
        retryEntity.setEventType(event.type().name());
        retryEntity.setPayload(serialize(event));
        retryEntity.setNotBefore(stageEntity.getNextRetryAt());
        outboxRepo.save(retryEntity);

        markDispatched(outboxId);
    }

    private void handleFatal(Long outboxId, PipelineEvent event, String stageName, String error) {
        var dlq = new DeadLetterEntity();
        dlq.setRunUuid(event.runUuid());
        dlq.setTenantId(event.tenantId());
        dlq.setSourceFqn(event.sourceFqn());
        dlq.setStageName(stageName);
        dlq.setEventType(event.type().name());
        dlq.setAttempts(1);
        dlq.setLastError(error);
        dlq.setPayload(serialize(event));
        dlqRepo.save(dlq);

        stageRepo.findByRunUuidAndStageName(event.runUuid(), stageName)
            .ifPresent(s -> {
                s.setStatus(PipelineStatus.DEAD_LETTER);
                s.setLastError(error);
                stageRepo.save(s);
            });

        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow();
        run.setStatus(PipelineStatus.DEAD_LETTER);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);

        outboxRepo.findById(outboxId).ifPresent(e -> {
            e.setStatus("FAILED");
            e.setLastError(error);
            outboxRepo.save(e);
        });
    }

    private String inferStageName(PipelineEventType type) {
        return switch (type) {
            case INGESTION_REQUESTED -> "ingestion";
            case RAW_DATA_INGESTED -> "discovery";
            case SCHEMA_DISCOVERED -> "profiling";
            case DATA_PROFILED -> "alignment";
            case SEMANTIC_ALIGNED -> "alignment";
        };
    }

    private PipelineEvent deserialize(String eventType, String payload) {
        try {
            return switch (PipelineEventType.valueOf(eventType)) {
                case INGESTION_REQUESTED -> mapper.readValue(payload, IngestionRequested.class);
                case RAW_DATA_INGESTED -> mapper.readValue(payload, RawDataIngested.class);
                case SCHEMA_DISCOVERED -> mapper.readValue(payload, SchemaDiscovered.class);
                case DATA_PROFILED -> mapper.readValue(payload, DataProfiled.class);
                case SEMANTIC_ALIGNED -> mapper.readValue(payload, SemanticAligned.class);
            };
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize event: " + eventType, e);
        }
    }

    private String serialize(PipelineEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }
}