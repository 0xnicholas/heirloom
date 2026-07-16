package com.heirloom.core.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SchemaDiscovered(
    List<String> discoveredTableFqns, int tableCount,
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent {

    @Override
    public PipelineEventType type() { return PipelineEventType.SCHEMA_DISCOVERED; }
}
