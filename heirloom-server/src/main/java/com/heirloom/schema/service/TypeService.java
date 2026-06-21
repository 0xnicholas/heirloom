package com.heirloom.schema.service;

import com.heirloom.schema.domain.ResourceType;
import com.heirloom.schema.dto.CreateTypeRequest;
import com.heirloom.service.EntityService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Business logic for ResourceType entities.
 * Was SchemaRegistryService — renamed and refactored to implement {@link EntityService}.
 * Structural validation moved to {@code TypeRepository.prepareInternal()}.
 */
@Service
public class TypeService implements EntityService<ResourceType> {

    @Override
    public ResourceType buildEntity(Object request) {
        if (request instanceof CreateTypeRequest req) {
            return req.toEntity();
        }
        throw new IllegalArgumentException("Expected CreateTypeRequest, got " + request.getClass());
    }

    @Override
    public void validateCreate(ResourceType entity) {
        // Business validation: uniqueness check is now in TypeRepository.prepareInternal
        // Other business rules added here as needed
    }

    @Override
    public void validateUpdate(ResourceType existing, ResourceType incoming) {
        // Business validation for updates
    }

    @Override
    public void validateDelete(ResourceType entity) {
        // Business validation for deletes (e.g., check no active instances)
    }

    @Override
    public Map<String, Object> toResponse(ResourceType entity, Set<String> fields) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("name", entity.getName());
        result.put("fullyQualifiedName", entity.getFullyQualifiedName());
        result.put("description", entity.getDescription() != null ? entity.getDescription() : "");
        result.put("domain", entity.getDomain());
        result.put("fields", entity.getFields());
        result.put("abilities", entity.getAbilities());
        result.put("stateMachine", entity.getStateMachine());
        result.put("relationships", entity.getRelationships());
        result.put("version", entity.getVersion());
        result.put("createdAt", entity.getCreatedAt());
        result.put("updatedAt", entity.getUpdatedAt());
        return result;
    }
}
