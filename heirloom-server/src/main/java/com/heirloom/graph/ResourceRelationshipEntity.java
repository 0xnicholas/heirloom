package com.heirloom.graph;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "resource_relationships")
public class ResourceRelationshipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_rid", nullable = false, length = 256)
    private String sourceRid;

    @Column(name = "target_rid", nullable = false, length = 256)
    private String targetRid;

    @Column(name = "relationship_type", nullable = false, length = 64)
    private String relationshipType;

    @Column(nullable = false, length = 32)
    private String semantics;  // OWNERSHIP, REFERENCE, ASSOCIATION

    @Column(columnDefinition = "jsonb", nullable = false)
    private String attributes = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    private Boolean deleted = false;

    public Long getId() { return id; }

    public String getSourceRid() { return sourceRid; }
    public void setSourceRid(String s) { this.sourceRid = s; }

    public String getTargetRid() { return targetRid; }
    public void setTargetRid(String t) { this.targetRid = t; }

    public String getRelationshipType() { return relationshipType; }
    public void setRelationshipType(String r) { this.relationshipType = r; }

    public String getSemantics() { return semantics; }
    public void setSemantics(String s) { this.semantics = s; }

    public String getAttributes() { return attributes; }
    public void setAttributes(String a) { this.attributes = a; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant t) { this.createdAt = t; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String c) { this.createdBy = c; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }

    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean d) { this.deleted = d; }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
