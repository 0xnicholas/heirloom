package com.heirloom.metadata.domain;

import com.heirloom.core.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "metadata_tables")
public class TableEntity implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    private String name;
    private String fullyQualifiedName;
    @Column(name = "database_service_fqn")
    private String databaseServiceFQN;
    @Column(name = "database_fqn")
    private String databaseFQN;
    @Column(name = "database_schema_fqn")
    private String databaseSchemaFQN;
    private String tableType = "BASE TABLE";

    @Column(name = "columns", columnDefinition = "jsonb")
    private String columnsJson = "[]";

    @Column(columnDefinition = "jsonb")
    private String tags = "[]";

    @Column(name = "domain_fqn")
    private String domainFQN;

    @Column(columnDefinition = "jsonb")
    private String constraints = "[]";

    @Column(name = "source_hash")
    private String sourceHash;

    private String lifecycle = "Created";

    @Column(columnDefinition = "jsonb")
    private String certification;

    private String description;
    private String owner;
    @Version private Long version = 1L;
    private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    // HeirloomEntity
    public Long getId() { return id; }
    public String getEntityType() { return "table"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String fqn) { this.fullyQualifiedName = fqn; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getOwner() { return owner; }
    public void setOwner(String o) { this.owner = o; }
    public Long getVersion() { return version; }
    public String getChangeHash() { return changeHash; }
    public void setChangeHash(String h) { this.changeHash = h; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean d) { this.deleted = d; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public String getDatabaseServiceFQN() { return databaseServiceFQN; }
    public void setDatabaseServiceFQN(String s) { this.databaseServiceFQN = s; }
    public String getDatabaseFQN() { return databaseFQN; }
    public void setDatabaseFQN(String d) { this.databaseFQN = d; }
    public String getDatabaseSchemaFQN() { return databaseSchemaFQN; }
    public void setDatabaseSchemaFQN(String s) { this.databaseSchemaFQN = s; }
    public String getTableType() { return tableType; }
    public void setTableType(String t) { this.tableType = t; }
    public String getColumnsJson() { return columnsJson; }
    public void setColumnsJson(String j) { this.columnsJson = j; }
    public String getTags() { return tags; }
    public void setTags(String t) { this.tags = t; }
    public String getDomainFQN() { return domainFQN; }
    public void setDomainFQN(String d) { this.domainFQN = d; }
    public String getConstraints() { return constraints; }
    public void setConstraints(String c) { this.constraints = c; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String s) { this.sourceHash = s; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String l) { this.lifecycle = l; }
    public String getCertification() { return certification; }
    public void setCertification(String c) { this.certification = c; }
}
