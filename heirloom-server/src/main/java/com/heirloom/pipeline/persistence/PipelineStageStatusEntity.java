package com.heirloom.pipeline.persistence;

import com.heirloom.core.pipeline.PipelineStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "pipeline_run_stages")
public class PipelineStageStatusEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "run_uuid", nullable = false) private UUID runUuid;
    @Column(name = "stage_name", nullable = false) private String stageName;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PipelineStatus status;
    @Column(nullable = false) private int attempts;
    @Column(name = "max_attempts", nullable = false) private int maxAttempts = 3;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "next_retry_at") private Instant nextRetryAt;
    @Column(name = "last_error", columnDefinition = "TEXT") private String lastError;

    public Long getId() { return id; }
    public UUID getRunUuid() { return runUuid; }
    public void setRunUuid(UUID u) { this.runUuid = u; }
    public String getStageName() { return stageName; }
    public void setStageName(String s) { this.stageName = s; }
    public PipelineStatus getStatus() { return status; }
    public void setStatus(PipelineStatus s) { this.status = s; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int a) { this.attempts = a; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int m) { this.maxAttempts = m; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant t) { this.completedAt = t; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant t) { this.nextRetryAt = t; }
    public String getLastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e; }
}
