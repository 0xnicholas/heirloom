package com.heirloom.repository;

import com.heirloom.metadata.domain.TableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TableJpaRepository extends JpaRepository<TableEntity, Long> {
    Optional<TableEntity> findByFullyQualifiedName(String fqn);
}
