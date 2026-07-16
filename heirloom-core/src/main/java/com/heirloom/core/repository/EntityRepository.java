package com.heirloom.core.repository;

import com.heirloom.core.entity.HeirloomEntity;
import java.util.Optional;

/**
 * Abstract base class for all Heirloom entity repositories.
 * Defines the core lifecycle contract — setFQN, prepare, store — without
 * any Spring or JPA dependency. The server layer provides the JPA-backed
 * implementation with transaction management.
 *
 * @param <E> the entity type, must implement {@link HeirloomEntity}
 */
public abstract class EntityRepository<E extends HeirloomEntity> {

    protected final String entityType;
    protected final Class<E> entityClass;

    protected EntityRepository(String entityType, Class<E> entityClass) {
        this.entityType = entityType;
        this.entityClass = entityClass;
    }

    // === Subclass must implement ===

    protected abstract void setFullyQualifiedName(E entity);
    protected abstract void prepareInternal(E entity, boolean isUpdate);

    // === Subclass may override ===

    protected void storeEntity(E entity, boolean isUpdate) {
        // Default no-op. Overridden in server layer to call JPA save.
    }

    protected void storeRelationships(E entity) {
        // Default no-op. Override when Graph Store is introduced.
    }

    // === Template methods (subclasses may override to add lifecycle hooks
    //     such as cache invalidation; the lifecycle body is delegated to
    //     {@link #doCreate} / {@link #doUpdate} so subclasses don't need to
    //     duplicate the steps.) ===

    public E create(E entity) {
        return doCreate(entity);
    }

    public E update(E entity) {
        return doUpdate(entity);
    }

    protected final E doCreate(E entity) {
        setFullyQualifiedName(entity);
        prepareInternal(entity, false);
        storeEntity(entity, false);
        storeRelationships(entity);
        return entity;
    }

    protected final E doUpdate(E entity) {
        setFullyQualifiedName(entity);
        prepareInternal(entity, true);
        storeEntity(entity, true);
        storeRelationships(entity);
        return entity;
    }

    // === Standard queries — abstract, implemented by server layer ===

    public abstract Optional<E> findById(Long id);
    public abstract void delete(Long id);
}
