package com.heirloom.repository;

import com.heirloom.entity.EntityRegistry;
import com.heirloom.security.domain.Role;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class RoleRepository extends EntityRepository<Role> {
    private final RoleJpaRepository jpa;
    public RoleRepository(RoleJpaRepository jpa) { super("role", Role.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register("role", Role.class, this, null, "{name}", "/v1/roles"); }
    @Override protected void setFullyQualifiedName(Role r) { r.setFullyQualifiedName(r.getName()); }
    @Override protected void prepareInternal(Role r, boolean isUpdate) {}
    public Optional<Role> findByName(String name) { return jpa.findByName(name); }
}
