package com.heirloom.repository;

import com.heirloom.metadata.domain.LineageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LineageJpaRepository extends JpaRepository<LineageEntity, Long> {
    List<LineageEntity> findByFromEntityFQN(String fqn);
    List<LineageEntity> findByToEntityFQN(String fqn);
}
