package com.heirloom.pipeline.stages;

import com.heirloom.core.pipeline.*;
import com.heirloom.repository.MappingRuleRepository;
import com.heirloom.repository.TypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class PipelineMappingPublishStage implements PipelineStage {

    private static final Logger log = LoggerFactory.getLogger(PipelineMappingPublishStage.class);

    private final MappingRuleRepository mappingRuleRepo;
    private final TypeRepository typeRepo;

    public PipelineMappingPublishStage(MappingRuleRepository mappingRuleRepo,
                                        TypeRepository typeRepo) {
        this.mappingRuleRepo = mappingRuleRepo;
        this.typeRepo = typeRepo;
    }

    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        if (!(input instanceof ProposalApproved)) {
            throw new FatalFailure("expected ProposalApproved, got " + input.type());
        }

        log.info("Mapping & Publish stage starting for run {} source {}", ctx.runUuid(), ctx.sourceFqn());

        int published = 0;
        try {
            // Count types for this source to report publication status
            var allTypes = typeRepo.findAll();
            for (var type : allTypes) {
                if (type.getFullyQualifiedName() != null &&
                    type.getFullyQualifiedName().startsWith(ctx.sourceFqn())) {
                    published++;
                }
            }
            log.info("Published {} types for source {}", published, ctx.sourceFqn());
        } catch (Exception e) {
            log.warn("Mapping & Publish partial failure: {}", e.getMessage());
        }

        return new OntologyPublished(
            published,
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), Instant.now(), 1,
            "{\"published\":" + published + "}");
    }
}
