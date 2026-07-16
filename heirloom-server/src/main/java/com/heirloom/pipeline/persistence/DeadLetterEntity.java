package com.heirloom.pipeline.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "pipeline_dead_letter")
public class DeadLetterEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "run_uuid", nullable = false) private UUID runUuid;
    @Column(name = "tenant_id", nullable = false) private String tenantId = "default";
    @Column(name = "source_fqn", nullable = false, length = 512) private String sourceFqn;
    @Column(name = "stage_name", nullable = false) private String stageName;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(nullable = false) private int attempts;
    @Column(name = "last_error", columnDefinition = "TEXT") private String lastError;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;
    @Column(name = "failed_at", nullable = false) private Instant failedAt = Instant.now();
    @Column(name = "replayed_at") private Instant replayedAt;
    @Column(name = "replayed_by") private String replayedBy;

    public Long getId() { return id; }
    public UUID getRunUuid() { return runUuid; }
    public void setRunUuid(UUID r) { this.runUuid = r; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String t) { this.tenantId = t; }
    public String getSourceFqn() { return sourceFqn; }
    public void setSourceFqn(String s) { this.sourceFqn = s; }
    public String getStageName() { return stageName; }
    public void setStageName(String s) { this.stageName = s; }
    public String getEventType() { return eventType; }
    public void setEventType(String t) { this.eventType = t; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int a) { this.attempts = a; }
    public String getLastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e; }
    public String getPayload() { return payload; }
    public void setPayload(String p) { this.payload = p; }
    public Instant getFailedAt() { return failedAt; }
    public void setFailedAt(Instant t) { this.failedAt = t; }
    public Instant getReplayedAt() { return replayedAt; }
    public void setReplayedAt(Instant t) { this.replayedAt = t; }
    public String getReplayedBy() { return replayedBy; }
    public void setReplayedBy(String s) { this.replayedBy = s; }
}
