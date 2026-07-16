package com.heirloom.service;

import com.heirloom.core.entity.HeirloomEntity;
import java.util.Map;
import java.util.Set;

/**
 * Business logic interface for each entity type.
 * Separate from structural validation (which happens in Repository.prepareInternal).
 */
public interface EntityService<E extends HeirloomEntity> {

    /** Build entity from creation request */
    E buildEntity(Object request);

    /** Business validation — uniqueness, authorization checks */
    void validateCreate(E entity);

    /** Business validation for updates */
    void validateUpdate(E existing, E incoming);

    /** Business validation for deletes */
    void validateDelete(E entity);

    /** Serialize entity for API response with field filtering */
    Map<String, Object> toResponse(E entity, Set<String> fields);
}
