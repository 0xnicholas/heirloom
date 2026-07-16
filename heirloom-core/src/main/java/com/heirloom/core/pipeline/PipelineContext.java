package com.heirloom.core.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public record PipelineContext(
    UUID runUuid,
    String tenantId,
    String sourceFqn,
    String correlationId,
    String stageName,
    int stageAttempt,
    Instant stageStartedAt,
    Clock clock
) {}