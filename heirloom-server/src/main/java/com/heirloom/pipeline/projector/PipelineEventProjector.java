package com.heirloom.pipeline.projector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.*;
import com.heirloom.pipeline.kafka.KafkaTopics;
import com.heirloom.pipeline.persistence.PipelineRunJpaRepository;
import com.heirloom.pipeline.persistence.PipelineStageStatusEntity;
import com.heirloom.pipeline.persistence.PipelineStageStatusJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

@Component
public class PipelineEventProjector {

    private static final Logger log = LoggerFactory.getLogger(PipelineEventProjector.class);

    private final PipelineRunJpaRepository runRepo;
    private final PipelineStageStatusJpaRepository stageRepo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final KafkaTemplate<String, PipelineEvent> kafkaTemplate;

    @Value("${heirloom.pipeline.kafka.topic-dlq}")
    private String dlqTopic;

    public PipelineEventProjector(PipelineRunJpaRepository runRepo,
                                   PipelineStageStatusJpaRepository stageRepo,
                                   ObjectMapper mapper,
                                   Clock clock,
                                   KafkaTemplate<String, PipelineEvent> kafkaTemplate) {
        this.runRepo = runRepo;
        this.stageRepo = stageRepo;
        this.mapper = mapper;
        this.clock = clock;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
        topics = "${heirloom.pipeline.kafka.topic-events}",
        groupId = KafkaTopics.GROUP_PROJECTOR
    )
    public void onEvent(PipelineEvent event) {
        try {
            switch (event.type()) {
                case INGESTION_REQUESTED -> onIngestionRequested(event);
                case RAW_DATA_INGESTED, SCHEMA_DISCOVERED, DATA_PROFILED, SEMANTIC_ALIGNED,
                     ENTITIES_RESOLVED, ONTOLOGY_PROPOSED, PROPOSAL_APPROVED,
                     PROPOSAL_REJECTED, ONTOLOGY_PUBLISHED
                    -> onStageEvent(event);
            }
        } catch (Exception e) {
            log.error("Projector failed for eventId={}: {}", event.eventId(), e.getMessage(), e);
            sendToDlq(event, "projector: " + e.getMessage());
        }
    }

    private void onIngestionRequested(PipelineEvent event) {
        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow(
            () -> new IllegalStateException("PipelineRun not found: " + event.runUuid()));
        run.setStatus(PipelineStatus.RUNNING);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);
        ensureStage(event.runUuid(), "ingestion");
    }

    private void onStageEvent(PipelineEvent event) {
        String stageName = inferStageName(event.type());
        ensureStage(event.runUuid(), stageName);
    }

    private void ensureStage(UUID runUuid, String stageName) {
        if (stageRepo.findByRunUuidAndStageName(runUuid, stageName).isEmpty()) {
            var s = new PipelineStageStatusEntity();
            s.setRunUuid(runUuid);
            s.setStageName(stageName);
            s.setStatus(PipelineStatus.PENDING);
            s.setAttempts(0);
            s.setMaxAttempts(3);
            stageRepo.save(s);
        }
    }

    private String inferStageName(PipelineEventType type) {
        return switch (type) {
            case RAW_DATA_INGESTED -> "discovery";
            case SCHEMA_DISCOVERED -> "profiling";
            case DATA_PROFILED -> "alignment";
            case SEMANTIC_ALIGNED -> "entity-resolution";
            case ENTITIES_RESOLVED -> "ontology-proposal";
            case ONTOLOGY_PROPOSED -> "governance";
            case PROPOSAL_APPROVED -> "mapping-publish";
            case PROPOSAL_REJECTED -> "mapping-publish";
            default -> "unknown";
        };
    }

    private void sendToDlq(PipelineEvent event, String reason) {
        kafkaTemplate.send(dlqTopic, event.tenantId() + "::" + event.sourceFqn(), event);
        log.warn("Event {} sent to DLQ: {}", event.eventId(), reason);
    }
}
