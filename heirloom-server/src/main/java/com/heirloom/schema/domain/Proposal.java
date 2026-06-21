package com.heirloom.schema.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

@Entity @Table(name = "proposals")
public class Proposal implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    private String name;
    private String fullyQualifiedName;
    private String targetEntityType;
    private String targetEntityFQN;
    @Column(columnDefinition = "jsonb") private String proposedChanges = "{}";
    private String changeType;
    private String status = "PENDING";
    private String source = "manual";
    private String proposedBy;
    private String reviewedBy;
    private String rejectionReason;
    private String description; private String owner;
    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(); private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "proposal"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String fqn) { this.fullyQualifiedName = fqn; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public String getOwner() { return owner; } public void setOwner(String o) { this.owner = o; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; } public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; } public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public String getTargetEntityType() { return targetEntityType; } public void setTargetEntityType(String t) { this.targetEntityType = t; }
    public String getTargetEntityFQN() { return targetEntityFQN; } public void setTargetEntityFQN(String f) { this.targetEntityFQN = f; }
    public String getProposedChanges() { return proposedChanges; } public void setProposedChanges(String c) { this.proposedChanges = c; }
    public String getChangeType() { return changeType; } public void setChangeType(String t) { this.changeType = t; }
    public String getStatus() { return status; } public void setStatus(String s) { this.status = s; }
    public String getSource() { return source; } public void setSource(String s) { this.source = s; }
    public String getProposedBy() { return proposedBy; } public void setProposedBy(String p) { this.proposedBy = p; }
    public String getReviewedBy() { return reviewedBy; } public void setReviewedBy(String r) { this.reviewedBy = r; }
    public String getRejectionReason() { return rejectionReason; } public void setRejectionReason(String r) { this.rejectionReason = r; }
}
