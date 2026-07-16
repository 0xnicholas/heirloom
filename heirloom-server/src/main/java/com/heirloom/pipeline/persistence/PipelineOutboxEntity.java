package com.heirloom.pipeline.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "pipeline_outbox")
public class PipelineOutboxEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "event_id", nullable = false, unique = true) private UUID eventId;
    @Column(name = "run_uuid", nullable = false) private UUID runUuid;
    @Column(name = "event_type", nullable = false) private String eventType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "claimed_at") private Instant claimedAt;
    @Column(name = "claimed_by", length = 128) private String claimedBy;
    @Column(name = "claimed_until") private Instant claimedUntil;
    @Column(name = "not_before") private Instant notBefore;
    @Column(name = "dispatched_at") private Instant dispatchedAt;
    @Column(nullable = false) private int attempts = 0;
    @Column(name = "last_error", columnDefinition = "TEXT") private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID e) { this.eventId = e; }
    public UUID getRunUuid() { return runUuid; }
    public void setRunUuid(UUID r) { this.runUuid = r; }
    public String getEventType() { return eventType; }
    public void setEventType(String t) { this.eventType = t; }
    public String getPayload() { return payload; }
    public void setPayload(String p) { this.payload = p; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant t) { this.claimedAt = t; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String s) { this.claimedBy = s; }
    public Instant getClaimedUntil() { return claimedUntil; }
    public void setClaimedUntil(Instant t) { this.claimedUntil = t; }
    public Instant getNotBefore() { return notBefore; }
    public void setNotBefore(Instant t) { this.notBefore = t; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant t) { this.dispatchedAt = t; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int a) { this.attempts = a; }
    public String getLastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e; }
    public Instant getCreatedAt() { return createdAt; }
}
