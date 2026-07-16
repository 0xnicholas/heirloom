package com.heirloom.pipeline.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity @Table(name = "pipeline_stage_executions")
@IdClass(PipelineStageExecutionEntity.PK.class)
public class PipelineStageExecutionEntity {
    @Id @Column(name = "input_event_id") private UUID inputEventId;
    @Id @Column(name = "stage_name") private String stageName;
    @Column(nullable = false) private String status;
    @Column(name = "output_event_id") private UUID outputEventId;
    @Column(name = "completed_at", nullable = false) private Instant completedAt = Instant.now();

    public PipelineStageExecutionEntity() {}
    public PipelineStageExecutionEntity(UUID inputEventId, String stageName,
                                         String status, UUID outputEventId) {
        this.inputEventId = inputEventId; this.stageName = stageName;
        this.status = status; this.outputEventId = outputEventId;
    }
    public UUID getInputEventId() { return inputEventId; }
    public String getStageName() { return stageName; }
    public String getStatus() { return status; }
    public UUID getOutputEventId() { return outputEventId; }
    public Instant getCompletedAt() { return completedAt; }

    public static class PK implements Serializable {
        private UUID inputEventId;
        private String stageName;
        public PK() {}
        public PK(UUID inputEventId, String stageName) {
            this.inputEventId = inputEventId; this.stageName = stageName;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(inputEventId, pk.inputEventId)
                && Objects.equals(stageName, pk.stageName);
        }
        @Override public int hashCode() { return Objects.hash(inputEventId, stageName); }
    }
}
