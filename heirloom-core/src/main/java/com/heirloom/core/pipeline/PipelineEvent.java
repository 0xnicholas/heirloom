package com.heirloom.core.pipeline;

import java.time.Instant;
import java.util.UUID;

public sealed interface PipelineEvent
    permits IngestionRequested, RawDataIngested, SchemaDiscovered,
            DataProfiled, SemanticAligned {

    UUID eventId();
    UUID runUuid();
    String tenantId();
    String sourceFqn();
    String correlationId();
    PipelineEventType type();
    Instant occurredAt();
    int payloadVersion();
    String payload();
}
