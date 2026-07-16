package com.heirloom.pipeline.stages;

import com.heirloom.core.alignment.AlignmentRequest;
import com.heirloom.core.alignment.AlignmentService;
import com.heirloom.core.pipeline.*;
import com.heirloom.pipeline.persistence.PipelineResultEntity;
import com.heirloom.pipeline.persistence.PipelineResultJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
public class PipelineAlignmentStage implements PipelineStage {

    private final AlignmentService alignmentService;
    private final PipelineResultJpaRepository resultRepo;
    private final ObjectMapper mapper;

    public PipelineAlignmentStage(AlignmentService alignmentService,
                                   PipelineResultJpaRepository resultRepo,
                                   ObjectMapper mapper) {
        this.alignmentService = alignmentService;
        this.resultRepo = resultRepo;
        this.mapper = mapper;
    }

    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        var request = new AlignmentRequest(ctx.sourceFqn(), java.util.List.of(), true);
        var map = alignmentService.align(request);

        resultRepo.deleteByRunUuidAndStageName(ctx.runUuid(), "alignment");
        try {
            resultRepo.save(new PipelineResultEntity(
                ctx.runUuid(), "alignment", "alignment_map",
                mapper.writeValueAsString(map)));
        } catch (Exception e) {
            throw new RuntimeException("failed to persist alignment result", e);
        }

        return new SemanticAligned(
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), Instant.now(), 1, "{}");
    }
}