package com.heirloom.schema.dto;

import com.heirloom.schema.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Request body for creating or updating a Resource Type.
 */
public record CreateTypeRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z][a-zA-Z0-9]*$",
                 message = "Type name must be PascalCase")
        String name,

        String description,

        @Valid
        List<Field> fields,

        List<Ability> abilities,

        @Valid
        List<StateTransition> stateMachine,

        @Valid
        List<Relationship> relationships
) {
    public CreateTypeRequest {
        fields = fields != null ? fields : List.of();
        abilities = abilities != null ? abilities : List.of();
        stateMachine = stateMachine != null ? stateMachine : List.of();
        relationships = relationships != null ? relationships : List.of();
    }

    /**
     * Apply this request to a ResourceType entity.
     */
    public ResourceType toEntity() {
        ResourceType entity = new ResourceType(name);
        entity.setDescription(description);
        entity.setFields(fields);
        entity.setAbilities(abilities);
        entity.setStateMachine(stateMachine);
        entity.setRelationships(relationships);
        return entity;
    }

    /**
     * Apply this request to an existing entity (update).
     */
    public void applyTo(ResourceType entity) {
        entity.setDescription(description);
        entity.setFields(fields);
        entity.setAbilities(abilities);
        entity.setStateMachine(stateMachine);
        entity.setRelationships(relationships);
    }
}
