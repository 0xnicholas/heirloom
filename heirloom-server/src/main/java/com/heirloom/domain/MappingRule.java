package com.heirloom.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "mapping_rules")
public class MappingRule implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    @Column(name = "type_fqn") private String typeFQN;
    @Column(name = "field_name") private String fieldName;
    @Column(name = "column_fqn") private String columnFQN;
    @Column(name = "mapping_source") private String mappingSource = "discovery";
    private String description;
    @Version private Long version = 1L; private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now(); private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "mappingRule"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String fqn) { this.fullyQualifiedName = fqn; }
    public String getName() { return typeFQN + "." + fieldName; }
    public String getDescription() { return description; } public void setDescription(String d) { this.description = d; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; } public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; } public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public String getTypeFQN() { return typeFQN; } public void setTypeFQN(String f) { this.typeFQN = f; }
    public String getFieldName() { return fieldName; } public void setFieldName(String f) { this.fieldName = f; }
    public String getColumnFQN() { return columnFQN; } public void setColumnFQN(String c) { this.columnFQN = c; }
    public String getMappingSource() { return mappingSource; } public void setMappingSource(String m) { this.mappingSource = m; }
}
