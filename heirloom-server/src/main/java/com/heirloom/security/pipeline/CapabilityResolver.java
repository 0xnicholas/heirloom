package com.heirloom.security.pipeline;

import com.heirloom.schema.domain.Ability;

import java.util.List;

/**
 * Abstracts capability resolution for the Gate step.
 */
public interface CapabilityResolver {

    /**
     * Resolve capabilities for an actor, filtering by required ability and resource type.
     */
    List<CapabilityRecord> resolve(String actorRole, Ability requiredAbility, String resourceTypeFqn);
}
