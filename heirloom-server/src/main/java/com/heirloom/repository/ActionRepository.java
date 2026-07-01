package com.heirloom.repository;

import com.heirloom.entity.EntityRegistry;
import com.heirloom.security.domain.Action;
import com.heirloom.security.validation.ActionValidator;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ActionRepository extends EntityRepository<Action> {

    private final ActionJpaRepository jpa;
    private final ActionValidator validator;

    public ActionRepository(ActionJpaRepository jpa, ActionValidator validator) {
        super("action", Action.class, jpa);
        this.jpa = jpa;
        this.validator = validator;
    }

    @PostConstruct
    void init() {
        EntityRegistry.register("action", Action.class, this, null, "{name}", "/v1/actions");
    }

    @Override
    protected void setFullyQualifiedName(Action a) {
        a.setFullyQualifiedName(a.getName());
    }

    @Override
    protected void prepareInternal(Action a, boolean isUpdate) {
        // I-1: Validate against ADR-007 rules at definition time
        validator.validate(a);
    }

    public Optional<Action> findByName(String name) {
        return jpa.findByName(name);
    }
}
