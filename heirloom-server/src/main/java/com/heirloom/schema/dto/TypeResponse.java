package com.heirloom.schema.dto;

import com.heirloom.schema.domain.*;

import java.time.Instant;
import java.util.List;

/**
 * Response body for a Resource Type.
 */
public record TypeResponse(
        String name,
        String description,
        List<Field> fields,
        List<Ability> abilities,
        List<StateTransition> stateMachine,
        List<Relationship> relationships,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static TypeResponse from(ResourceType entity) {
        return new TypeResponse(
                entity.getName(),
                entity.getDescription(),
                entity.getFields(),
                entity.getAbilities(),
                entity.getStateMachine(),
                entity.getRelationships(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
