package com.heirloom.metadata.repository;

import com.heirloom.metadata.domain.ColumnProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ColumnProfileJpaRepository extends JpaRepository<ColumnProfileEntity, Long> {
    List<ColumnProfileEntity> findByTableFQNAndColumnNameOrderByProfiledAtDesc(String tableFQN, String columnName);
    List<ColumnProfileEntity> findByTableFQNOrderByProfiledAtDesc(String tableFQN);
}
