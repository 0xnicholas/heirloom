package com.heirloom.security.pipeline;

import com.heirloom.schema.domain.Ability;

import java.time.Instant;

/**
 * A resolved capability record — lightweight, not a JPA entity.
 * Parsed from Role.capabilities JSONB by TypeSafeCapabilityResolver.
 */
public record CapabilityRecord(
        Ability ability,
        String resourceType,
        Instant expiry
) {}
