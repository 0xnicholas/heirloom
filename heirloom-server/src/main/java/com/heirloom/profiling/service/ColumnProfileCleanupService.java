package com.heirloom.profiling.service;

import com.heirloom.metadata.repository.ColumnProfileJpaRepository;
import org.springframework.stereotype.Service;

@Service
public class ColumnProfileCleanupService {
    private final ColumnProfileJpaRepository jpa;
    private static final int MAX_HISTORY = 5;

    public ColumnProfileCleanupService(ColumnProfileJpaRepository jpa) { this.jpa = jpa; }

    public void cleanup(String tableFQN, String columnName) {
        var all = jpa.findByTableFQNAndColumnNameOrderByProfiledAtDesc(tableFQN, columnName);
        if (all.size() > MAX_HISTORY) {
            var toDelete = all.subList(MAX_HISTORY, all.size());
            jpa.deleteAll(toDelete);
        }
    }
}
