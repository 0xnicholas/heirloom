package com.heirloom.repository;

import com.heirloom.entity.EntityRegistry;
import com.heirloom.security.domain.Function;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class FunctionRepository extends EntityRepository<Function> {
    private final FunctionJpaRepository jpa;
    public FunctionRepository(FunctionJpaRepository jpa) { super("function", Function.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register("function", Function.class, this, null, "{name}", "/v1/functions"); }
    @Override protected void setFullyQualifiedName(Function f) { f.setFullyQualifiedName(f.getName()); }
    @Override protected void prepareInternal(Function f, boolean isUpdate) {}
    public Optional<Function> findByName(String name) { return jpa.findByName(name); }
}
