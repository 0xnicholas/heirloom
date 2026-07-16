package com.heirloom.repository;

import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.security.domain.Capability;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

@Repository
public class CapabilityRepository extends EntityRepository<Capability> {
    private final CapabilityJpaRepository jpa;
    public CapabilityRepository(CapabilityJpaRepository jpa) { super("capability", Capability.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register("capability", Capability.class, this, null, "{actor}.{entityType}.{op}", "/v1/capabilities"); }
    @Override protected void setFullyQualifiedName(Capability c) { c.setFullyQualifiedName(c.getActorName() + "." + c.getTargetEntityType() + "." + c.getOperation()); }
    @Override protected void prepareInternal(Capability c, boolean isUpdate) {}
}
