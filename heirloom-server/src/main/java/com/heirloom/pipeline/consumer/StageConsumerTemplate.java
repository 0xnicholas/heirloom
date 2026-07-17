package com.heirloom.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.*;
import com.heirloom.pipeline.kafka.KafkaTopics;
import com.heirloom.pipeline.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public abstract class StageConsumerTemplate {

    private static final Logger log = LoggerFactory.getLogger(StageConsumerTemplate.class);

    /** Phase 8 全局固定 8 个 stage 名。 */
    public static final List<String> ALL_STAGE_NAMES =
        List.of("ingestion", "discovery", "profiling", "alignment",
            "entity-resolution", "ontology-proposal", "governance", "mapping-publish");

    private final PipelineRunJpaRepository runRepo;
    private final PipelineStageStatusJpaRepository stageRepo;
    private final PipelineStageExecutionJpaRepository execRepo;
    private final DeadLetterJpaRepository dlqRepo;
    private final PipelineResultJpaRepository resultRepo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final KafkaTemplate<String, PipelineEvent> kafkaTemplate;

    @Value("${heirloom.pipeline.kafka.topic-events}")
    private String eventsTopic;

    @Value("${heirloom.pipeline.kafka.topic-dlq}")
    private String dlqTopic;

    protected StageConsumerTemplate(PipelineRunJpaRepository runRepo,
                                      PipelineStageStatusJpaRepository stageRepo,
                                      PipelineStageExecutionJpaRepository execRepo,
                                      DeadLetterJpaRepository dlqRepo,
                                      PipelineResultJpaRepository resultRepo,
                                      ObjectMapper mapper,
                                      Clock clock,
                                      KafkaTemplate<String, PipelineEvent> kafkaTemplate) {
        this.runRepo = runRepo;
        this.stageRepo = stageRepo;
        this.execRepo = execRepo;
        this.dlqRepo = dlqRepo;
        this.resultRepo = resultRepo;
        this.mapper = mapper;
        this.clock = clock;
        this.kafkaTemplate = kafkaTemplate;
    }

    protected abstract String stageName();
    protected abstract PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx);

    @KafkaListener(
        topics = "${heirloom.pipeline.kafka.topic-events}",
        groupId = "#{T(com.heirloom.pipeline.kafka.KafkaTopics).groupIdForStage(stageName())}"
    )
    public void onEvent(PipelineEvent event) {
        String name = stageName();

        // 0. listener 只处理匹配 event.type() 的事件（消费端过滤）
        if (!expectedEventType(name).equals(event.type())) {
            log.debug("Listener for stage {} skipping event of type {}", name, event.type());
            return;
        }

        // 1. 幂等检查
        if (execRepo.existsByInputEventIdAndStageNameAndStatus(
                event.eventId(), name, "COMPLETED")) {
            log.debug("Skipping already-completed event {} for stage {}", event.eventId(), name);
            return;
        }

        // 2. 更新 run + stage 状态为 RUNNING
        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow();
        run.setStatus(PipelineStatus.RUNNING);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);

        var stage = ensureStage(event.runUuid(), name);
        stage.setStatus(PipelineStatus.RUNNING);
        stage.setAttempts(stage.getAttempts() + 1);
        stage.setStartedAt(clock.instant());
        stageRepo.save(stage);

        // 3. 调用子类业务逻辑
        var ctx = new PipelineContext(event.runUuid(), event.tenantId(), event.sourceFqn(),
            event.correlationId(), name, stage.getAttempts(),
            clock.instant(), clock);
        try {
            PipelineEvent nextEvent = applyStage(event, ctx);

            // 4a. 成功
            stage.setStatus(PipelineStatus.COMPLETED);
            stage.setCompletedAt(clock.instant());
            stageRepo.save(stage);
            execRepo.save(new PipelineStageExecutionEntity(
                event.eventId(), name, "COMPLETED",
                nextEvent != null ? nextEvent.eventId() : null));

            if (nextEvent != null) {
                kafkaTemplate.send(eventsTopic,
                    nextEvent.tenantId() + "::" + nextEvent.sourceFqn(),
                    nextEvent);
            } else {
                if (allStagesComplete(event.runUuid())) {
                    run.setStatus(PipelineStatus.COMPLETED);
                    run.setCompletedAt(clock.instant());
                    run.setUpdatedAt(clock.instant());
                    runRepo.save(run);
                }
            }
        } catch (RecoverableFailure rf) {
            handleRecoverable(event, stage, rf);
        } catch (FatalFailure pf) {
            handleFatal(event, stage, pf.getMessage());
        } catch (PipelineFailure pf) {
            handleFatal(event, stage, pf.getMessage());
        } catch (Exception e) {
            handleFatal(event, stage, "unexpected: " + e.getMessage());
        }
    }

    private PipelineStageStatusEntity ensureStage(UUID runUuid, String stageName) {
        return stageRepo.findByRunUuidAndStageName(runUuid, stageName).orElseGet(() -> {
            var s = new PipelineStageStatusEntity();
            s.setRunUuid(runUuid);
            s.setStageName(stageName);
            s.setStatus(PipelineStatus.PENDING);
            s.setAttempts(0);
            s.setMaxAttempts(3);
            return stageRepo.save(s);
        });
    }

    private PipelineEventType expectedEventType(String stageName) {
        return switch (stageName) {
            case "ingestion" -> PipelineEventType.INGESTION_REQUESTED;
            case "discovery" -> PipelineEventType.RAW_DATA_INGESTED;
            case "profiling" -> PipelineEventType.SCHEMA_DISCOVERED;
            case "alignment" -> PipelineEventType.DATA_PROFILED;
            case "entity-resolution" -> PipelineEventType.SEMANTIC_ALIGNED;
            case "ontology-proposal" -> PipelineEventType.ENTITIES_RESOLVED;
            case "governance" -> PipelineEventType.ONTOLOGY_PROPOSED;
            case "mapping-publish" -> PipelineEventType.PROPOSAL_APPROVED;
            default -> throw new IllegalStateException("unknown stage: " + stageName);
        };
    }

    private void handleRecoverable(PipelineEvent event,
                                    PipelineStageStatusEntity stage,
                                    RecoverableFailure rf) {
        if (stage.getAttempts() >= stage.getMaxAttempts()) {
            handleFatal(event, stage, "max attempts: " + rf.getMessage());
            return;
        }
        long backoffSec = Math.min((long) Math.pow(2, stage.getAttempts()) * 10, 300);
        stage.setStatus(PipelineStatus.RETRYING);
        stage.setNextRetryAt(clock.instant().plusSeconds(backoffSec));
        stage.setLastError(rf.getMessage());
        stageRepo.save(stage);

        // 立即重发（同 partition key 保证顺序）
        kafkaTemplate.send(eventsTopic,
            event.tenantId() + "::" + event.sourceFqn(), event);
        log.info("Stage {} retry {} for event {} (next in {}s)",
            stage.getStageName(), stage.getAttempts(), event.eventId(), backoffSec);
    }

    private void handleFatal(PipelineEvent event,
                               PipelineStageStatusEntity stage, String error) {
        // 双写 DLQ：topic 先发（廉价、快），表写失败时不重试 topic
        kafkaTemplate.send(dlqTopic,
            event.tenantId() + "::" + event.sourceFqn(), event);

        var dlq = new DeadLetterEntity();
        dlq.setRunUuid(event.runUuid());
        dlq.setTenantId(event.tenantId());
        dlq.setSourceFqn(event.sourceFqn());
        dlq.setStageName(stage.getStageName());
        dlq.setEventType(event.type().name());
        dlq.setAttempts(stage.getAttempts());
        dlq.setLastError(error);
        try {
            dlq.setPayload(mapper.writeValueAsString(event));
        } catch (Exception e) {
            dlq.setPayload("{\"error\":\"serialize failed\"}");
        }
        dlqRepo.save(dlq);

        stage.setStatus(PipelineStatus.DEAD_LETTER);
        stage.setLastError(error);
        stageRepo.save(stage);

        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow();
        run.setStatus(PipelineStatus.DEAD_LETTER);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);
    }

    private boolean allStagesComplete(UUID runUuid) {
        var stages = stageRepo.findByRunUuid(runUuid);
        if (stages.size() != ALL_STAGE_NAMES.size()) {
            return false;
        }
        return stages.stream()
            .allMatch(s -> s.getStatus() == PipelineStatus.COMPLETED)
            && ALL_STAGE_NAMES.stream().allMatch(required ->
                stages.stream().anyMatch(s -> s.getStageName().equals(required)));
    }
}
