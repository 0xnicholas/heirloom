package com.heirloom.metadata.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "metadata_lineage")
public class LineageEntity implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    private String name;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    @Column(name = "from_entity_fqn") private String fromEntityFQN;
    @Column(name = "to_entity_fqn") private String toEntityFQN;
    private String lineageType = "table_lineage";
    @Column(columnDefinition = "jsonb") private String columnMappings = "[]";
    private String source = "fk_inference";
    private String description;
    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(); private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "lineage"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; } public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; } public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public String getFromEntityFQN() { return fromEntityFQN; } public void setFromEntityFQN(String f) { this.fromEntityFQN = f; }
    public String getToEntityFQN() { return toEntityFQN; } public void setToEntityFQN(String t) { this.toEntityFQN = t; }
    public String getLineageType() { return lineageType; } public void setLineageType(String t) { this.lineageType = t; }
    public String getSource() { return source; } public void setSource(String s) { this.source = s; }
    public String getColumnMappings() { return columnMappings; } public void setColumnMappings(String c) { this.columnMappings = c; }
}
