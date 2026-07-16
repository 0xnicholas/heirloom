package com.heirloom.core.metadata;

import com.heirloom.core.entity.HeirloomEntity;

public interface Domain extends HeirloomEntity {
    String getName();
    String getParentFQN();
    String getDescription();
    String getOwner();
}
