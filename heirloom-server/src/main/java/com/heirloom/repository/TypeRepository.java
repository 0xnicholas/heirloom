package com.heirloom.repository;

import com.heirloom.entity.EntityRegistry;
import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.service.TypeValidationException;
import com.heirloom.schema.service.TypeValidator;
import jakarta.annotation.PostConstruct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * ResourceType repository — extends {@link EntityRepository} with Heirloom-specific lifecycle.
 * <p>
 * Structural validation (TypeValidator) runs in {@link #prepareInternal}, not in the Service layer.
 * Registers itself with EntityRegistry in {@link #init()}.
 */
@Repository
public class TypeRepository extends EntityRepository<ResourceType> {

    private final ResourceTypeJpaRepository jpa;

    public TypeRepository(ResourceTypeJpaRepository jpa) {
        super(EntityRegistry.RESOURCE_TYPE, ResourceType.class, jpa);
        this.jpa = jpa;
    }

    @PostConstruct
    void init() {
        EntityRegistry.register(
            EntityRegistry.RESOURCE_TYPE, ResourceType.class, this, null,
            "{domain}.{name}", "/v1/resourceTypes");
    }

    // === Lifecycle hooks ===

    @Override
    protected void setFullyQualifiedName(ResourceType type) {
        String domain = type.getDomain() != null ? type.getDomain() : "default";
        type.setFullyQualifiedName(domain + EntityRegistry.FQN_SEPARATOR + type.getName());
    }

    @Override
    protected void prepareInternal(ResourceType type, boolean isUpdate) {
        Map<String, ResourceType> knownTypes = new HashMap<>();
        jpa.findAll().forEach(t -> knownTypes.put(t.getName(), t));

        List<TypeValidator.Diagnostic> diagnostics = TypeValidator.validate(type, knownTypes);

        List<TypeValidator.Diagnostic> errors = diagnostics.stream()
            .filter(d -> d.severity() == TypeValidator.Severity.ERROR)
            .toList();

        if (!errors.isEmpty()) {
            throw new TypeValidationException(errors);
        }
    }

    @Override
    protected void storeEntity(ResourceType entity, boolean isUpdate) {
        // Compute changeHash for incremental detection
        entity.setChangeHash(computeHash(entity));
        jpa.save(entity);
    }

    public List<ResourceType> findAll() {
        return jpa.findAll();
    }

    public Optional<ResourceType> findByFQN(String fqn) {
        return jpa.findByFullyQualifiedName(fqn);
    }

    public Optional<ResourceType> findByName(String name) {
        return jpa.findByName(name);
    }

    public boolean existsByName(String name) {
        return jpa.existsByName(name);
    }

    private String computeHash(ResourceType type) {
        String source = type.getName() + type.getFields() + type.getAbilities()
            + type.getStateMachine() + type.getRelationships();
        return Integer.toHexString(source.hashCode());
    }
}
