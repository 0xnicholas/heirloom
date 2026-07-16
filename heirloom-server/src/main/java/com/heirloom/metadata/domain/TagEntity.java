package com.heirloom.metadata.domain;

import com.heirloom.core.entity.HeirloomEntity;
import com.heirloom.core.metadata.Tag;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "metadata_tags")
public class TagEntity implements HeirloomEntity, Tag {
    @Id @GeneratedValue private Long id;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    private String name;
    @Column(name = "classification_fqn") private String classificationFQN;
    @Column(name = "parent_fqn") private String parentFQN;
    @Column(name = "style") private String style;
    private String description;
    @Version private Long version = 1L;
    private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "tag"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getClassificationFQN() { return classificationFQN; } public void setClassificationFQN(String c) { this.classificationFQN = c; }
    public String getParentFQN() { return parentFQN; } public void setParentFQN(String p) { this.parentFQN = p; }
    public String getStyle() { return style; } public void setStyle(String s) { this.style = s; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; } public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; } public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
}
