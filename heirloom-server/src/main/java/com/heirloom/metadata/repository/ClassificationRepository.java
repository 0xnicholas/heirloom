package com.heirloom.metadata.repository;

import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.metadata.domain.ClassificationEntity;
import com.heirloom.repository.EntityRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ClassificationRepository extends EntityRepository<ClassificationEntity> {

    private final ClassificationJpaRepository jpa;

    public ClassificationRepository(ClassificationJpaRepository jpa) {
        super("classification", ClassificationEntity.class, jpa);
        this.jpa = jpa;
    }

    @PostConstruct
    void init() {
        EntityRegistry.register("classification", ClassificationEntity.class, this, null,
                "{name}", "/v1/classifications");
    }

    @Override
    protected void setFullyQualifiedName(ClassificationEntity entity) {
        entity.setFullyQualifiedName(entity.getName());
    }

    @Override
    protected void prepareInternal(ClassificationEntity entity, boolean isUpdate) {
        // no-op
    }

    public Optional<ClassificationEntity> findByName(String name) {
        return jpa.findByName(name);
    }

    public Optional<ClassificationEntity> findByFullyQualifiedName(String fqn) {
        return jpa.findByFullyQualifiedName(fqn);
    }

    public List<ClassificationEntity> findAll() {
        return jpa.findAll();
    }
}
