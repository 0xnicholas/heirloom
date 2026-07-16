package com.heirloom.repository;

import com.heirloom.domain.Resource;
import com.heirloom.core.entity.EntityRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Repository for Resource instances.
 * Extends {@link EntityRepository} for standard CRUD lifecycle hooks,
 * and adds RID-based lookup, typed listing, and field-level filtering.
 */
@Repository
public class ResourceRepository extends EntityRepository<Resource> {

    private final ResourceJpaRepository jpa;

    @PersistenceContext
    private EntityManager em;

    public ResourceRepository(ResourceJpaRepository jpa) {
        super("resource", Resource.class, jpa);
        this.jpa = jpa;
    }

    @PostConstruct
    void init() {
        EntityRegistry.register("resource", Resource.class,
                this, null, "{rid}", "/v1/resources");
    }

    @Override
    protected void setFullyQualifiedName(Resource r) {
        r.setFullyQualifiedName(r.getRid());
    }

    @Override
    protected void prepareInternal(Resource r, boolean isUpdate) {
        // Fields are validated by ResourceService before save
    }

    public Optional<Resource> findByRid(String rid) {
        return jpa.findByRid(rid);
    }

    /**
     * List resources with optional filters.
     * Uses parameterized JPQL to avoid SQL injection.
     */
    @Transactional(readOnly = true)
    public List<Resource> list(String type, String state, Map<String, String> fieldFilters,
                                int limit, int offset) {
        // fieldFilters reserved for future GIN index queries; currently unused
        StringBuilder jpql = new StringBuilder("SELECT r FROM Resource r WHERE r.deleted = false");
        Map<String, Object> params = new LinkedHashMap<>();

        if (type != null && !type.isBlank()) {
            jpql.append(" AND r.resourceType = :type");
            params.put("type", type);
        }
        if (state != null && !state.isBlank()) {
            jpql.append(" AND r.currentState = :state");
            params.put("state", state);
        }

        TypedQuery<Resource> query = em.createQuery(jpql.toString(), Resource.class);
        params.forEach(query::setParameter);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    @Transactional(readOnly = true)
    public long count(String type, String state) {
        StringBuilder jpql = new StringBuilder("SELECT COUNT(r) FROM Resource r WHERE r.deleted = false");
        Map<String, Object> params = new LinkedHashMap<>();

        if (type != null && !type.isBlank()) {
            jpql.append(" AND r.resourceType = :type");
            params.put("type", type);
        }
        if (state != null && !state.isBlank()) {
            jpql.append(" AND r.currentState = :state");
            params.put("state", state);
        }

        TypedQuery<Long> query = em.createQuery(jpql.toString(), Long.class);
        params.forEach(query::setParameter);
        return query.getSingleResult();
    }
}
