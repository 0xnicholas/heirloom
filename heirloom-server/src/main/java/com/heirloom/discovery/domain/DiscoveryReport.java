package com.heirloom.discovery.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "discovery_reports")
public class DiscoveryReport implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    private String name, fullyQualifiedName, sourceFQN, status = "RUNNING";
    private Integer tablesScanned = 0, tablesFailed = 0, metadataCreated = 0, proposalsGenerated = 0, proposalsRegistered = 0;
    private String contentHash; private Long durationMs;
    @Column(columnDefinition = "jsonb") private String errorSummary = "[]";
    private Boolean partialSuccess = false;
    private String description, owner;
    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(), updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "discoveryReport"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public String getOwner() { return owner; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; } public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public String getSourceFQN() { return sourceFQN; } public void setSourceFQN(String s) { this.sourceFQN = s; }
    public String getStatus() { return status; } public void setStatus(String s) { this.status = s; }
    public Integer getTablesScanned() { return tablesScanned; } public void setTablesScanned(Integer t) { this.tablesScanned = t; }
    public Integer getTablesFailed() { return tablesFailed; } public void setTablesFailed(Integer t) { this.tablesFailed = t; }
    public Integer getMetadataCreated() { return metadataCreated; } public void setMetadataCreated(Integer m) { this.metadataCreated = m; }
    public Integer getProposalsGenerated() { return proposalsGenerated; } public void setProposalsGenerated(Integer p) { this.proposalsGenerated = p; }
    public Integer getProposalsRegistered() { return proposalsRegistered; } public void setProposalsRegistered(Integer p) { this.proposalsRegistered = p; }
    public String getContentHash() { return contentHash; } public void setContentHash(String c) { this.contentHash = c; }
    public Long getDurationMs() { return durationMs; } public void setDurationMs(Long d) { this.durationMs = d; }
    public Boolean getPartialSuccess() { return partialSuccess; } public void setPartialSuccess(Boolean p) { this.partialSuccess = p; }
    public String getErrorSummary() { return errorSummary; } public void setErrorSummary(String e) { this.errorSummary = e; }

    public static DiscoveryReport start(DiscoverySource src) {
        DiscoveryReport r = new DiscoveryReport();
        r.sourceFQN = src.getFullyQualifiedName();
        r.fullyQualifiedName = src.getFullyQualifiedName() + "." + System.currentTimeMillis();
        r.status = "RUNNING";
        return r;
    }
}
