package com.heirloom.metadata.domain;

import com.heirloom.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "table_profiles")
public class TableProfileEntity implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    private String tableFQN;
    private Long rowCount;
    private Long sizeInBytes;
    private Instant freshness;
    @Column(columnDefinition = "jsonb") private String columnProfiles = "[]";
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "tableProfile"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
    public String getName() { return "profile-" + id; }
    public String getDescription() { return null; }
    public Long getVersion() { return 1L; }
    public String getChangeHash() { return null; }
    public Boolean getDeleted() { return false; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return createdAt; }

    public String getTableFQN() { return tableFQN; } public void setTableFQN(String f) { this.tableFQN = f; }
    public Long getRowCount() { return rowCount; } public void setRowCount(Long r) { this.rowCount = r; }
    public Long getSizeInBytes() { return sizeInBytes; } public void setSizeInBytes(Long s) { this.sizeInBytes = s; }
    public Instant getFreshness() { return freshness; } public void setFreshness(Instant f) { this.freshness = f; }
    public String getColumnProfiles() { return columnProfiles; } public void setColumnProfiles(String c) { this.columnProfiles = c; }
}
