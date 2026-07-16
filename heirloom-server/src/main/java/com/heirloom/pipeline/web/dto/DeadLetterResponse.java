package com.heirloom.pipeline.web.dto;

import com.heirloom.pipeline.persistence.DeadLetterEntity;
import java.time.Instant;
import java.util.UUID;

public record DeadLetterResponse(
    Long id,
    UUID runUuid,
    String sourceFqn,
    String stageName,
    String eventType,
    int attempts,
    String lastError,
    Instant failedAt,
    Instant replayedAt
) {
    public static DeadLetterResponse from(DeadLetterEntity e) {
        return new DeadLetterResponse(e.getId(), e.getRunUuid(), e.getSourceFqn(),
            e.getStageName(), e.getEventType(), e.getAttempts(),
            e.getLastError(), e.getFailedAt(), e.getReplayedAt());
    }
}