package com.heirloom.web;

import com.heirloom.auth.Authorizer;
import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.core.entity.HeirloomEntity;
import com.heirloom.core.repository.EntityRepository;
import com.heirloom.service.EntityService;

/**
 * Abstract REST controller base — modeled on OpenMetadata's {@code EntityResource<T,K>}.
 * Provides standard CRUD template methods. Concrete resources declare
 * {@code @RequestMapping} with concrete types and delegate to these methods.
 *
 * @param <E> the entity type, must implement {@link HeirloomEntity}
 */
public abstract class EntityResource<E extends HeirloomEntity> {

    protected final String entityType;
    protected final EntityRepository<E> repository;
    protected final EntityService<E> service;
    protected final Authorizer authorizer;

    @SuppressWarnings("unchecked")
    protected EntityResource(String entityType, Authorizer authorizer) {
        this.entityType = entityType;
        this.authorizer = authorizer;
        this.repository = (EntityRepository<E>) EntityRegistry.getRepository(entityType);
        this.service = (EntityService<E>) EntityRegistry.getService(entityType);
    }

    // === Template methods — subclasses delegate to these ===

    protected E findEntityByFQN(String fqn) {
        // Default: rely on repository having a findByFQN method
        // Override in subclass if needed
        throw new UnsupportedOperationException("findByFQN not implemented for " + entityType);
    }
}
