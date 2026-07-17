package com.heirloom.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.PipelineContext;
import com.heirloom.core.pipeline.PipelineEvent;
import com.heirloom.pipeline.persistence.*;
import com.heirloom.pipeline.stages.PipelineIngestionStage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class PipelineIngestionListener extends StageConsumerTemplate {

    private final PipelineIngestionStage stage;

    public PipelineIngestionListener(PipelineRunJpaRepository runRepo,
                                      PipelineStageStatusJpaRepository stageRepo,
                                      PipelineStageExecutionJpaRepository execRepo,
                                      DeadLetterJpaRepository dlqRepo,
                                      PipelineResultJpaRepository resultRepo,
                                      ObjectMapper mapper,
                                      Clock clock,
                                      KafkaTemplate<String, PipelineEvent> kafkaTemplate,
                                      PipelineIngestionStage stage) {
        super(runRepo, stageRepo, execRepo, dlqRepo, resultRepo, mapper, clock, kafkaTemplate);
        this.stage = stage;
    }

    @Override
    protected String stageName() { return "ingestion"; }

    @Override
    protected PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx) {
        return stage.apply(input, ctx);
    }
}
