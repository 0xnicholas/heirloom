package com.heirloom.core.pipeline;

import com.fasterxml.jackson.annotation.JsonGetter;
import java.time.Instant;
import java.util.UUID;

public record OntologyProposed(
    int proposalCount,
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent {

    @Override
    @JsonGetter("type")
    public PipelineEventType type() { return PipelineEventType.ONTOLOGY_PROPOSED; }
}
