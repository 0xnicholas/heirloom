package com.heirloom.metadata.domain;

import com.heirloom.core.entity.HeirloomEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "column_profiles")
public class ColumnProfileEntity implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    @Column(name = "table_fqn") private String tableFQN;
    @Column(name = "column_name") private String columnName;
    @Column(name = "profiled_at") private Instant profiledAt = Instant.now();
    @Column(name = "null_count") private Long nullCount;
    @Column(name = "null_rate") private Double nullRate;
    @Column(name = "distinct_count") private Long distinctCount;
    @Column(name = "distinct_rate") private Double distinctRate;
    @Column(name = "empty_string_count") private Long emptyStringCount;
    @Column(name = "min_value") private String minValue;
    @Column(name = "max_value") private String maxValue;
    @Column(name = "avg_length") private Double avgLength;
    @Column(name = "top_values", columnDefinition = "jsonb") private String topValues;
    @Column(name = "detected_class") private String detectedClass;
    @Column(name = "quality_score") private Double qualityScore;
    @Column(name = "fully_qualified_name") private String fullyQualifiedName;
    private String name;
    private String description;
    private String owner;
    @Version private Long version = 1L;
    private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getEntityType() { return "columnProfile"; }
    public String getFullyQualifiedName() { return fullyQualifiedName; }
    public void setFullyQualifiedName(String f) { this.fullyQualifiedName = f; }
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

    public String getTableFQN() { return tableFQN; }
    public void setTableFQN(String t) { this.tableFQN = t; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String c) { this.columnName = c; }
    public Instant getProfiledAt() { return profiledAt; }
    public void setProfiledAt(Instant p) { this.profiledAt = p; }
    public Long getNullCount() { return nullCount; }
    public void setNullCount(Long n) { this.nullCount = n; }
    public Double getNullRate() { return nullRate; }
    public void setNullRate(Double n) { this.nullRate = n; }
    public Long getDistinctCount() { return distinctCount; }
    public void setDistinctCount(Long d) { this.distinctCount = d; }
    public Double getDistinctRate() { return distinctRate; }
    public void setDistinctRate(Double d) { this.distinctRate = d; }
    public Long getEmptyStringCount() { return emptyStringCount; }
    public void setEmptyStringCount(Long e) { this.emptyStringCount = e; }
    public String getMinValue() { return minValue; }
    public void setMinValue(String m) { this.minValue = m; }
    public String getMaxValue() { return maxValue; }
    public void setMaxValue(String m) { this.maxValue = m; }
    public Double getAvgLength() { return avgLength; }
    public void setAvgLength(Double a) { this.avgLength = a; }
    public String getTopValues() { return topValues; }
    public void setTopValues(String t) { this.topValues = t; }
    public String getDetectedClass() { return detectedClass; }
    public void setDetectedClass(String d) { this.detectedClass = d; }
    public Double getQualityScore() { return qualityScore; }
    public void setQualityScore(Double q) { this.qualityScore = q; }
}
