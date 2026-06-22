package com.heirloom.repository;

import com.heirloom.entity.HeirloomEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/**
 * Abstract base class for all Heirloom entity repositories.
 * Wraps Spring Data JPA — adds lifecycle hooks (prepare, setFQN, storeRelationships)
 * without replacing JPA's save/findById.
 *
 * @param <E> the entity type, must implement {@link HeirloomEntity}
 */
public abstract class EntityRepository<E extends HeirloomEntity> {

    protected final String entityType;
    protected final Class<E> entityClass;
    protected final JpaRepository<E, Long> jpaRepository;

    protected EntityRepository(String entityType, Class<E> entityClass,
                               JpaRepository<E, Long> jpaRepository) {
        this.entityType = entityType;
        this.entityClass = entityClass;
        this.jpaRepository = jpaRepository;
    }

    // === Subclass must implement ===

    protected abstract void setFullyQualifiedName(E entity);
    protected abstract void prepareInternal(E entity, boolean isUpdate);

    // === Subclass may override ===

    protected void storeEntity(E entity, boolean isUpdate) {
        jpaRepository.save(entity);
    }

    protected void storeRelationships(E entity) {
        // Default no-op. Override when Graph Store is introduced.
    }

    // === Template methods (subclasses may override to add lifecycle hooks
    //     such as cache invalidation; the lifecycle body is delegated to
    //     {@link #doCreate} / {@link #doUpdate} so subclasses don't need to
    //     duplicate the steps.) ===

    @Transactional
    public E create(E entity) {
        return doCreate(entity);
    }

    @Transactional
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

    // === Standard queries delegate to JPA ===

    public Optional<E> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Transactional
    public void delete(Long id) {
        jpaRepository.deleteById(id);
    }
}
