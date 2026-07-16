package com.heirloom.core.metadata;

import com.heirloom.core.entity.HeirloomEntity;

public interface Tag extends HeirloomEntity {
    String getName();
    String getClassificationFQN();
    String getParentFQN();
    String getStyle();
    String getDescription();
}
