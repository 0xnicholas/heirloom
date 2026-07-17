package com.heirloom.pipeline.stages;

import com.heirloom.core.pipeline.*;
import com.heirloom.repository.ProposalJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class PipelineGovernanceStage implements PipelineStage {

    private static final Logger log = LoggerFactory.getLogger(PipelineGovernanceStage.class);

    private final ProposalJpaRepository proposalJpa;

    public PipelineGovernanceStage(ProposalJpaRepository proposalJpa) {
        this.proposalJpa = proposalJpa;
    }

    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        if (!(input instanceof OntologyProposed)) {
            throw new FatalFailure("expected OntologyProposed, got " + input.type());
        }

        log.info("Governance stage starting for run {} source {}", ctx.runUuid(), ctx.sourceFqn());

        int approved = 0;
        var pendingProposals = proposalJpa.findByStatus("PENDING");
        for (var proposal : pendingProposals) {
            try {
                proposal.setStatus("APPROVED");
                proposal.setReviewedBy("pipeline-auto");
                proposalJpa.save(proposal);
                approved++;
                log.debug("Auto-approved proposal {}", proposal.getId());
            } catch (Exception e) {
                log.warn("Failed to approve proposal {}: {}", proposal.getId(), e.getMessage());
            }
        }

        if (approved > 0) {
            return new ProposalApproved(
                approved,
                UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
                ctx.correlationId(), Instant.now(), 1,
                "{\"approved\":" + approved + "}");
        } else {
            return new ProposalRejected(
                0, "no pending proposals to approve",
                UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
                ctx.correlationId(), Instant.now(), 1,
                "{\"rejected\":0}");
        }
    }
}
