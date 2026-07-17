package com.heirloom.core.pipeline;

import com.fasterxml.jackson.annotation.JsonGetter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EntitiesResolved(
    List<String> resolvedTableFqns, int entityCount,
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent {

    @Override
    @JsonGetter("type")
    public PipelineEventType type() { return PipelineEventType.ENTITIES_RESOLVED; }
}
