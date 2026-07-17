package com.heirloom.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.*;
import com.heirloom.pipeline.persistence.*;
import com.heirloom.pipeline.stages.PipelineMappingPublishStage;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class PipelineMappingPublishListener extends StageConsumerTemplate {

    private final PipelineMappingPublishStage stage;

    public PipelineMappingPublishListener(PipelineRunJpaRepository runRepo,
                                           PipelineStageStatusJpaRepository stageRepo,
                                           PipelineStageExecutionJpaRepository execRepo,
                                           DeadLetterJpaRepository dlqRepo,
                                           PipelineResultJpaRepository resultRepo,
                                           ObjectMapper mapper,
                                           Clock clock,
                                           KafkaTemplate<String, PipelineEvent> kafkaTemplate,
                                           PipelineMappingPublishStage stage) {
        super(runRepo, stageRepo, execRepo, dlqRepo, resultRepo, mapper, clock, kafkaTemplate);
        this.stage = stage;
    }

    @Override
    protected String stageName() { return "mapping-publish"; }

    @Override
    protected PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx) {
        return stage.apply(input, ctx);
    }
}
