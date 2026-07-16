package com.heirloom.core.metadata;

import com.heirloom.core.entity.HeirloomEntity;
import java.time.Instant;

public interface TableProfileDef extends HeirloomEntity {
    String getTableFQN();
    Long getRowCount();
    Long getSizeInBytes();
    Instant getFreshness();
    Instant getProfiledAt();
    Long getProfilingDurationMs();
    Long getNullCount();
    Long getDistinctCount();
    Long getDuplicateRowCount();
    String getColumnProfiles();
    Double getOverallQualityScore();
}
