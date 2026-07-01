package com.heirloom.domain;

import com.heirloom.entity.HeirloomEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Resource instance — the runtime embodiment of a ResourceType.
 * Stores semantic metadata (RID, type, owner, state, version) plus
 * business fields as a JSONB map. The ResourceType definition governs
 * which fields are allowed and what state transitions are legal.
 */
@Entity
@Table(name = "heirloom_resources")
public class Resource implements HeirloomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 256)
    private String rid;

    @Column(name = "resource_type", nullable = false, length = 128)
    private String resourceType;

    @Column(length = 256)
    private String owner;

    @Column(name = "current_state", nullable = false, length = 64)
    private String currentState;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> fields = new LinkedHashMap<>();

    @Column(name = "fully_qualified_name", length = 512)
    private String fullyQualifiedName;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "change_hash", length = 64)
    private String changeHash;

    @Column(nullable = false)
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Resource() {
        // JPA
    }

    public Resource(String rid, String resourceType, String owner, String currentState) {
        this.rid = rid;
        this.resourceType = resourceType;
        this.owner = owner;
        this.currentState = currentState;
        this.fields = new LinkedHashMap<>();
        this.version = 0L;
        this.deleted = false;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        if (this.fullyQualifiedName == null) {
            this.fullyQualifiedName = this.rid;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- HeirloomEntity ---

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public String getEntityType() {
        return "resource";
    }

    @Override
    public String getFullyQualifiedName() {
        return fullyQualifiedName;
    }

    @Override
    public void setFullyQualifiedName(String fqn) {
        this.fullyQualifiedName = fqn;
    }

    @Override
    public String getName() {
        return rid;
    }

    @Override
    public String getDescription() {
        return "Resource of type " + resourceType;
    }

    @Override
    public Long getVersion() {
        return version;
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String getOwner() {
        return owner;
    }

    @Override
    public String getChangeHash() {
        return changeHash;
    }

    @Override
    public Boolean getDeleted() {
        return deleted;
    }

    // --- Getters / Setters ---

    public String getRid() {
        return rid;
    }

    public void setRid(String rid) {
        this.rid = rid;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields != null ? fields : new LinkedHashMap<>();
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public void setChangeHash(String changeHash) {
        this.changeHash = changeHash;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
