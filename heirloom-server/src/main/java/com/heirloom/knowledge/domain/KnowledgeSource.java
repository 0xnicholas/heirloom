package com.heirloom.knowledge.domain;

import com.heirloom.core.entity.HeirloomEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;
import java.time.Instant;

@Entity @Table(name = "knowledge_sources")
public class KnowledgeSource implements HeirloomEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 256) private String name;
    @Column(name = "fully_qualified_name", length = 512) private String fullyQualifiedName;
    @Column(name = "source_type", nullable = false, length = 64) private String sourceType;
    @Column(nullable = false, length = 1024) private String path;
    @Column(length = 256) private String branch = "main";
    @Type(JsonType.class) @Column(columnDefinition = "jsonb") private String config = "{}";
    @Column(length = 64) private String schedule = "manual";
    @Column(length = 32) private String status = "ACTIVE";
    @Column(columnDefinition = "text") private String description;
    @Column(length = 256) private String owner;
    @Version private Long version = 1L;
    @Column(name = "change_hash", length = 64) private String changeHash;
    @Column(nullable = false) private Boolean deleted = false;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;

    @PrePersist void prePersist() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }

    @Override public Long getId() { return id; }
    @Override public String getEntityType() { return "knowledgeSource"; }
    @Override public String getFullyQualifiedName() { return fullyQualifiedName; }
    @Override public void setFullyQualifiedName(String f) { fullyQualifiedName = f; }
    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public Long getVersion() { return version; }
    @Override public Instant getCreatedAt() { return createdAt; }
    @Override public Instant getUpdatedAt() { return updatedAt; }
    @Override public String getOwner() { return owner; }
    @Override public String getChangeHash() { return changeHash; }
    @Override public Boolean getDeleted() { return deleted; }
    public void setName(String n) { name = n; }
    public void setSourceType(String s) { sourceType = s; }
    public void setPath(String p) { path = p; }
    public void setBranch(String b) { branch = b; }
    public void setConfig(String c) { config = c; }
    public void setSchedule(String s) { schedule = s; }
    public void setStatus(String s) { status = s; }
    public void setDescription(String d) { description = d; }
    public void setOwner(String o) { owner = o; }
    public void setChangeHash(String h) { changeHash = h; }
    public void setDeleted(Boolean d) { deleted = d; }
    public String getSourceType() { return sourceType; }
    public String getPath() { return path; }
    public String getBranch() { return branch; }
    public String getConfig() { return config; }
    public String getSchedule() { return schedule; }
    public String getStatus() { return status; }
}
