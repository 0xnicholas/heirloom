package com.heirloom.pipeline.stages;

import com.heirloom.core.alignment.AlignmentMap;
import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.pipeline.*;
import com.heirloom.core.profiling.ProfileReport;
import com.heirloom.discovery.inference.InferenceContext;
import com.heirloom.discovery.inference.InferencePipeline;
import com.heirloom.repository.ProposalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class PipelineOntologyProposalStage implements PipelineStage {

    private static final Logger log = LoggerFactory.getLogger(PipelineOntologyProposalStage.class);

    private final InferencePipeline inferencePipeline;
    private final ProposalRepository proposalRepo;

    public PipelineOntologyProposalStage(InferencePipeline inferencePipeline,
                                          ProposalRepository proposalRepo) {
        this.inferencePipeline = inferencePipeline;
        this.proposalRepo = proposalRepo;
    }

    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        if (!(input instanceof EntitiesResolved)) {
            throw new FatalFailure("expected EntitiesResolved, got " + input.type());
        }

        log.info("Ontology proposal stage starting for run {} source {}", ctx.runUuid(), ctx.sourceFqn());

        // Build inference context from available data
        var inferenceCtx = new InferenceContext(
            null,
            null,
            null,
            List.of(),
            ctx.sourceFqn());
        var proposals = inferencePipeline.infer(inferenceCtx);

        int proposalCount = 0;
        for (var proposal : proposals) {
            try {
                var entity = proposal.toProposal();
                proposalRepo.create(entity);
                proposalCount++;
                log.debug("Created proposal for type {}", proposal.proposedTypeName());
            } catch (Exception e) {
                log.warn("Failed to create proposal for {}: {}", proposal.proposedTypeName(), e.getMessage());
            }
        }

        return new OntologyProposed(
            proposalCount,
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), Instant.now(), 1,
            "{\"proposalsCreated\":" + proposalCount + "}");
    }
}
