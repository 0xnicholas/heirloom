package com.heirloom.pipeline.stages;

import com.heirloom.core.pipeline.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class PipelineOrchestrator {

    private final PipelineStageRegistry registry;
    private final PipelineIngestionStage ingestion;
    private final PipelineDiscoveryStage discovery;
    private final PipelineProfilingStage profiling;
    private final PipelineAlignmentStage alignment;

    public PipelineOrchestrator(PipelineStageRegistry registry,
                                  PipelineIngestionStage ingestion,
                                  PipelineDiscoveryStage discovery,
                                  PipelineProfilingStage profiling,
                                  PipelineAlignmentStage alignment) {
        this.registry = registry;
        this.ingestion = ingestion;
        this.discovery = discovery;
        this.profiling = profiling;
        this.alignment = alignment;
    }

    @PostConstruct
    void wire() {
        registry.register(PipelineEventType.INGESTION_REQUESTED, ingestion);
        registry.register(PipelineEventType.RAW_DATA_INGESTED, discovery);
        registry.register(PipelineEventType.SCHEMA_DISCOVERED, profiling);
        registry.register(PipelineEventType.DATA_PROFILED, alignment);
        registry.register(PipelineEventType.SEMANTIC_ALIGNED, (event, ctx) -> null);
    }
}