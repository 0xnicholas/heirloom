package com.heirloom.pipeline.stages;

import com.heirloom.core.pipeline.*;
import com.heirloom.core.profiling.ProfilingService;
import com.heirloom.pipeline.persistence.PipelineResultJpaRepository;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
public class PipelineProfilingStage implements PipelineStage {

    private final ProfilingService profilingService;
    private final PipelineResultJpaRepository resultRepo;

    public PipelineProfilingStage(ProfilingService profilingService,
                                   PipelineResultJpaRepository resultRepo) {
        this.profilingService = profilingService;
        this.resultRepo = resultRepo;
    }

    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        if (!(input instanceof SchemaDiscovered discovered)) {
            throw new FatalFailure("expected SchemaDiscovered, got " + input.type());
        }
        int profiled = 0;
        for (String tableFQN : discovered.discoveredTableFqns()) {
            try {
                profilingService.profile(tableFQN);
                profiled++;
            } catch (Exception e) {
                // partial failure — continue
            }
        }
        return new DataProfiled(
            discovered.discoveredTableFqns(), profiled, 0.0,
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), Instant.now(), 1,
            "{\"profiled\":" + profiled + "}");
    }
}