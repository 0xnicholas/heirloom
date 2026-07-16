package com.heirloom.metadata.repository;

import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.metadata.domain.TagEntity;
import com.heirloom.repository.EntityRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TagRepository extends EntityRepository<TagEntity> {

    private final TagJpaRepository jpa;

    public TagRepository(TagJpaRepository jpa) {
        super("tag", TagEntity.class, jpa);
        this.jpa = jpa;
    }

    @PostConstruct
    void init() {
        EntityRegistry.register("tag", TagEntity.class, this, null,
                "{name}", "/v1/tags");
    }

    @Override
    protected void setFullyQualifiedName(TagEntity entity) {
        entity.setFullyQualifiedName(entity.getName());
    }

    @Override
    protected void prepareInternal(TagEntity entity, boolean isUpdate) {
        // no-op
    }

    public Optional<TagEntity> findByName(String name) {
        return jpa.findByName(name);
    }

    public Optional<TagEntity> findByFullyQualifiedName(String fqn) {
        return jpa.findByFullyQualifiedName(fqn);
    }

    public List<TagEntity> findAll() {
        return jpa.findAll();
    }
}
