package com.heirloom.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.*;
import com.heirloom.pipeline.persistence.*;
import com.heirloom.pipeline.stages.PipelineOntologyProposalStage;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class PipelineOntologyProposalListener extends StageConsumerTemplate {

    private final PipelineOntologyProposalStage stage;

    public PipelineOntologyProposalListener(PipelineRunJpaRepository runRepo,
                                             PipelineStageStatusJpaRepository stageRepo,
                                             PipelineStageExecutionJpaRepository execRepo,
                                             DeadLetterJpaRepository dlqRepo,
                                             PipelineResultJpaRepository resultRepo,
                                             ObjectMapper mapper,
                                             Clock clock,
                                             KafkaTemplate<String, PipelineEvent> kafkaTemplate,
                                             PipelineOntologyProposalStage stage) {
        super(runRepo, stageRepo, execRepo, dlqRepo, resultRepo, mapper, clock, kafkaTemplate);
        this.stage = stage;
    }

    @Override
    protected String stageName() { return "ontology-proposal"; }

    @Override
    protected PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx) {
        return stage.apply(input, ctx);
    }
}
