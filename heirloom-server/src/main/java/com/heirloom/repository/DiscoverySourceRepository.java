package com.heirloom.repository;

import com.heirloom.discovery.domain.DiscoverySource;
import com.heirloom.core.entity.EntityRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class DiscoverySourceRepository extends EntityRepository<DiscoverySource> {
    private final DiscoverySourceJpaRepository jpa;
    public DiscoverySourceRepository(DiscoverySourceJpaRepository jpa) { super(EntityRegistry.DISCOVERY_SOURCE, DiscoverySource.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register(EntityRegistry.DISCOVERY_SOURCE, DiscoverySource.class, this, null, "{env}.{name}", "/v1/discovery/sources"); }
    @Override protected void setFullyQualifiedName(DiscoverySource s) { s.setFullyQualifiedName((s.getEnvironment() != null ? s.getEnvironment() : "prod") + "." + s.getName()); }
    @Override protected void prepareInternal(DiscoverySource s, boolean isUpdate) {}
    public Optional<DiscoverySource> findByFQN(String fqn) { return jpa.findByFullyQualifiedName(fqn); }
}
