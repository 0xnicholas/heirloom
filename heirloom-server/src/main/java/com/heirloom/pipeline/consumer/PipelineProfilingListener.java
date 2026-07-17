package com.heirloom.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.*;
import com.heirloom.pipeline.persistence.*;
import com.heirloom.pipeline.stages.PipelineProfilingStage;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class PipelineProfilingListener extends StageConsumerTemplate {

    private final PipelineProfilingStage stage;

    public PipelineProfilingListener(PipelineRunJpaRepository runRepo,
                                      PipelineStageStatusJpaRepository stageRepo,
                                      PipelineStageExecutionJpaRepository execRepo,
                                      DeadLetterJpaRepository dlqRepo,
                                      PipelineResultJpaRepository resultRepo,
                                      ObjectMapper mapper,
                                      Clock clock,
                                      KafkaTemplate<String, PipelineEvent> kafkaTemplate,
                                      PipelineProfilingStage stage) {
        super(runRepo, stageRepo, execRepo, dlqRepo, resultRepo, mapper, clock, kafkaTemplate);
        this.stage = stage;
    }

    @Override
    protected String stageName() { return "profiling"; }

    @Override
    protected PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx) {
        return stage.apply(input, ctx);
    }
}
