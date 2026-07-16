package com.heirloom.metadata.repository;

import com.heirloom.metadata.domain.ClassificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ClassificationJpaRepository extends JpaRepository<ClassificationEntity, Long> {
    Optional<ClassificationEntity> findByFullyQualifiedName(String fqn);
    Optional<ClassificationEntity> findByName(String name);
}
