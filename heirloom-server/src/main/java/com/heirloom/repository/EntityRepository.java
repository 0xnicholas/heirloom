package com.heirloom.repository;

import com.heirloom.core.entity.HeirloomEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/**
 * JPA-backed base class for all Heirloom entity repositories.
 * Extends the core lifecycle contract with Spring Data JPA persistence
 * and transaction management.
 *
 * @param <E> the entity type, must implement {@link HeirloomEntity}
 */
public abstract class EntityRepository<E extends HeirloomEntity>
        extends com.heirloom.core.repository.EntityRepository<E> {

    protected final JpaRepository<E, Long> jpaRepository;

    protected EntityRepository(String entityType, Class<E> entityClass,
                               JpaRepository<E, Long> jpaRepository) {
        super(entityType, entityClass);
        this.jpaRepository = jpaRepository;
    }

    @Override
    protected void storeEntity(E entity, boolean isUpdate) {
        jpaRepository.save(entity);
    }

    @Override
    @Transactional
    public E create(E entity) {
        return super.create(entity);
    }

    @Override
    @Transactional
    public E update(E entity) {
        return super.update(entity);
    }

    @Override
    public Optional<E> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        jpaRepository.deleteById(id);
    }
}
