package com.heirloom.pipeline.stages;

import com.heirloom.core.pipeline.*;
import com.heirloom.ontology.service.CrossOntologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class PipelineEntityResolutionStage implements PipelineStage {

    private static final Logger log = LoggerFactory.getLogger(PipelineEntityResolutionStage.class);

    private final CrossOntologyService crossOntologyService;

    public PipelineEntityResolutionStage(CrossOntologyService crossOntologyService) {
        this.crossOntologyService = crossOntologyService;
    }

    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        if (!(input instanceof SemanticAligned)) {
            throw new FatalFailure("expected SemanticAligned, got " + input.type());
        }

        log.info("Entity resolution stage starting for run {} source {}", ctx.runUuid(), ctx.sourceFqn());
        int resolvedCount = 0;
        try {
            // Resolve entities for this source across all ontologies
            var ontologies = crossOntologyService.listOntologies();
            int total = 0;
            for (var onto : ontologies) {
                try {
                    var resolved = crossOntologyService.resolve(onto.getName(), ctx.sourceFqn(), onto.getName());
                    if (resolved.isPresent()) total++;
                } catch (Exception ex) {
                    log.debug("No resolution for ontology {}: {}", onto.getName(), ex.getMessage());
                }
            }
            resolvedCount = total;
            log.info("Resolved {} entity mappings for source {}", resolvedCount, ctx.sourceFqn());
        } catch (Exception e) {
            log.warn("Entity resolution partial failure (non-fatal): {}", e.getMessage());
        }

        return new EntitiesResolved(
            List.of(), resolvedCount,
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), Instant.now(), 1,
            "{\"resolved\":" + resolvedCount + "}");
    }
}
