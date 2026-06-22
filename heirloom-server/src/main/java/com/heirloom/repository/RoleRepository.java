package com.heirloom.repository;

import com.heirloom.entity.EntityRegistry;
import com.heirloom.security.RoleCapabilityCache;
import com.heirloom.security.domain.Role;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class RoleRepository extends EntityRepository<Role> {
    private final RoleJpaRepository jpa;
    private final RoleCapabilityCache capabilityCache;

    public RoleRepository(RoleJpaRepository jpa,
                          @Lazy RoleCapabilityCache capabilityCache) {
        super("role", Role.class, jpa);
        this.jpa = jpa;
        this.capabilityCache = capabilityCache;
    }

    @PostConstruct void init() { EntityRegistry.register("role", Role.class, this, null, "{name}", "/v1/roles"); }
    @Override protected void setFullyQualifiedName(Role r) { r.setFullyQualifiedName(r.getName()); }
    @Override protected void prepareInternal(Role r, boolean isUpdate) {}

    public Optional<Role> findByName(String name) { return jpa.findByName(name); }

    // === Cache invalidation hooks (Phase 2.3) ===
    // The capability cache is read-through — mutations must drop stale entries
    // so the next authorization check picks up the new capabilities.

    @Override
    public Role create(Role entity) {
        Role saved = super.doCreate(entity);
        capabilityCache.invalidate(saved.getName());
        return saved;
    }

    @Override
    public Role update(Role entity) {
        Role saved = super.doUpdate(entity);
        capabilityCache.invalidate(saved.getName());
        return saved;
    }

    @Override
    public void delete(Long id) {
        findById(id).ifPresent(r -> capabilityCache.invalidate(r.getName()));
        super.delete(id);
    }
}