package com.heirloom.metadata.repository;

import com.heirloom.metadata.domain.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TagJpaRepository extends JpaRepository<TagEntity, Long> {
    Optional<TagEntity> findByFullyQualifiedName(String fqn);
    Optional<TagEntity> findByName(String name);
}
