package com.heirloom.knowledge.service;

import com.heirloom.metadata.domain.TableEntity;
import java.util.List;

public record DiscoveryCompletedEvent(
    String sourceFqn,
    List<TableEntity> tables,
    List<?> reports
) {}
