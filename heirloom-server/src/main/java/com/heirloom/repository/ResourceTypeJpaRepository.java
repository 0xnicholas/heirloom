package com.heirloom.repository;

import com.heirloom.schema.domain.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * Spring Data JPA repository for ResourceType.
 * Separate interface (not inner class) so Spring Data can discover it.
 */
public interface ResourceTypeJpaRepository extends JpaRepository<ResourceType, Long> {
    Optional<ResourceType> findByName(String name);
    Optional<ResourceType> findByFullyQualifiedName(String fqn);
    boolean existsByName(String name);
}
