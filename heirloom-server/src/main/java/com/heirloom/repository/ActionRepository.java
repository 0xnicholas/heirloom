package com.heirloom.repository;

import com.heirloom.entity.EntityRegistry;
import com.heirloom.security.domain.Action;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class ActionRepository extends EntityRepository<Action> {
    private final ActionJpaRepository jpa;
    public ActionRepository(ActionJpaRepository jpa) { super("action", Action.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register("action", Action.class, this, null, "{name}", "/v1/actions"); }
    @Override protected void setFullyQualifiedName(Action a) { a.setFullyQualifiedName(a.getName()); }
    @Override protected void prepareInternal(Action a, boolean isUpdate) {}
    public Optional<Action> findByName(String name) { return jpa.findByName(name); }
}
