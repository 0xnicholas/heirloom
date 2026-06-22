package com.heirloom.repository;

import com.heirloom.discovery.domain.DiscoverySource;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DiscoverySourceJpaRepository extends JpaRepository<DiscoverySource, Long> {
    Optional<DiscoverySource> findByFullyQualifiedName(String fqn);
}
