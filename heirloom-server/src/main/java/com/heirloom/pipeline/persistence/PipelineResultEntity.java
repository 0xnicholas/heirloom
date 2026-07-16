package com.heirloom.pipeline.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "pipeline_run_results")
public class PipelineResultEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "run_uuid", nullable = false) private UUID runUuid;
    @Column(name = "stage_name", nullable = false) private String stageName;
    @Column(name = "result_type", nullable = false) private String resultType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private String result;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public PipelineResultEntity() {}
    public PipelineResultEntity(UUID runUuid, String stageName, String resultType, String result) {
        this.runUuid = runUuid; this.stageName = stageName;
        this.resultType = resultType; this.result = result;
    }
    public Long getId() { return id; }
    public UUID getRunUuid() { return runUuid; }
    public String getStageName() { return stageName; }
    public String getResultType() { return resultType; }
    public String getResult() { return result; }
    public Instant getCreatedAt() { return createdAt; }
}
