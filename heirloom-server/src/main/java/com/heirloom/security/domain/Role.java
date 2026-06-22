package com.heirloom.security.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "security_roles")
public class Role implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    private String name;
    private String scope = "ontology";
    @Column(columnDefinition = "text") private String capabilities = "[]";
    @Column(name = "knowledge_restrictions", columnDefinition = "jsonb")
    private String knowledgeRestrictions;
    private String description;
    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(); private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "role"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; }
    public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; } public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; } public void setUpdatedAt(Instant u) { this.updatedAt = u; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getScope() { return scope; } public void setScope(String s) { this.scope = s; }
    public String getCapabilities() { return capabilities; } public void setCapabilities(String c) { this.capabilities = c; }
    public String getKnowledgeRestrictions() { return knowledgeRestrictions; }
    public void setKnowledgeRestrictions(String r) { this.knowledgeRestrictions = r; }
}