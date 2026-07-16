package com.heirloom.pipeline.stages;

import com.heirloom.core.pipeline.*;
import com.heirloom.duckdb.DuckDbSyncService;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PipelineIngestionStage implements PipelineStage {

    private final DuckDbSyncService syncService;

    public PipelineIngestionStage(DuckDbSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        if (!(input instanceof IngestionRequested req)) {
            throw new FatalFailure("expected IngestionRequested, got " + input.type());
        }
        List<String> ingested = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String tableFQN : req.tableFqns()) {
            try {
                syncService.sync(tableFQN);
                ingested.add(tableFQN);
            } catch (Exception e) {
                failed.add(tableFQN);
            }
        }
        if (ingested.isEmpty()) {
            throw new RecoverableFailure(
                "all " + req.tableFqns().size() + " table syncs failed");
        }
        return new RawDataIngested(
            ingested, Instant.now(),
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), Instant.now(), 1,
            "{\"ingested\":" + ingested.size() + ",\"failed\":" + failed.size() + "}");
    }
}