package com.heirloom.core.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RawDataIngested(
    List<String> ingestedTableFqns, Instant syncedAt,
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent {

    @Override
    public PipelineEventType type() { return PipelineEventType.RAW_DATA_INGESTED; }
}
