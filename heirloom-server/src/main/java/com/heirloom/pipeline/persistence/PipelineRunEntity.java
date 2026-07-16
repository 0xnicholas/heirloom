package com.heirloom.pipeline.persistence;

import com.heirloom.core.pipeline.PipelineStatus;
import com.heirloom.core.pipeline.PipelineTriggerType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "pipeline_runs")
public class PipelineRunEntity {

    @Id @GeneratedValue private Long id;

    @Column(name = "run_uuid", nullable = false, unique = true)
    private UUID runUuid;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId = "default";

    @Column(name = "source_fqn", nullable = false)
    private String sourceFqn;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private PipelineStatus status;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Enumerated(EnumType.STRING) @Column(name = "trigger_type", nullable = false)
    private PipelineTriggerType triggerType;

    @Column(name = "table_fqns", columnDefinition = "TEXT")
    private String tableFqns;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public Long getId() { return id; }
    public UUID getRunUuid() { return runUuid; }
    public void setRunUuid(UUID u) { this.runUuid = u; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String t) { this.tenantId = t; }
    public String getSourceFqn() { return sourceFqn; }
    public void setSourceFqn(String s) { this.sourceFqn = s; }
    public PipelineStatus getStatus() { return status; }
    public void setStatus(PipelineStatus s) { this.status = s; }
    public UUID getCorrelationId() { return correlationId; }
    public void setCorrelationId(UUID c) { this.correlationId = c; }
    public PipelineTriggerType getTriggerType() { return triggerType; }
    public void setTriggerType(PipelineTriggerType t) { this.triggerType = t; }
    public String getTableFqns() { return tableFqns; }
    public void setTableFqns(String t) { this.tableFqns = t; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant t) { this.completedAt = t; }
}
