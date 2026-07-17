package com.heirloom.core.pipeline;

import com.fasterxml.jackson.annotation.JsonGetter;
import java.time.Instant;
import java.util.UUID;

public sealed interface PipelineEvent
    permits IngestionRequested, RawDataIngested, SchemaDiscovered,
            DataProfiled, SemanticAligned, EntitiesResolved,
            OntologyProposed, ProposalApproved, ProposalRejected,
            OntologyPublished {

    UUID eventId();
    UUID runUuid();
    String tenantId();
    String sourceFqn();
    String correlationId();

    @JsonGetter("type")
    PipelineEventType type();

    Instant occurredAt();
    int payloadVersion();
    String payload();
}
