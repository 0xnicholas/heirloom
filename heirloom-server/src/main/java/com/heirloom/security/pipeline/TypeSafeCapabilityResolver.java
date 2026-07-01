package com.heirloom.security.pipeline;

import com.heirloom.schema.domain.Ability;
import com.heirloom.security.RoleCapabilityCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Type-safe capability resolver — wraps RoleCapabilityCache and parses
 * Role.capabilities JSONB into structured CapabilityRecord objects.
 * Supports both old format ({@code entityType/operation}) and new format
 * ({@code ability/resourceType}) with best-effort mapping.
 */
@Component
public class TypeSafeCapabilityResolver implements CapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(TypeSafeCapabilityResolver.class);

    private static final Map<String, Ability> OPERATION_MAP = Map.of(
            "query", Ability.QUERY,
            "mutate", Ability.MUTATE,
            "drop", Ability.DROP,
            "transfer", Ability.TRANSFER,
            "copy", Ability.COPY,
            "freeze", Ability.FREEZE
    );

    private final RoleCapabilityCache cache;

    public TypeSafeCapabilityResolver(RoleCapabilityCache cache) {
        this.cache = cache;
    }

    @Override
    public List<CapabilityRecord> resolve(String actorRole, Ability requiredAbility, String resourceTypeFqn) {
        List<Map<String, String>> raw = cache.get(actorRole);
        if (raw == null || raw.isEmpty()) return List.of();

        return raw.stream()
                .map(this::parse)
                .filter(c -> c.ability() == requiredAbility)
                .filter(c -> "*".equals(c.resourceType()) || resourceTypeFqn.equals(c.resourceType()))
                .filter(c -> c.expiry() == null || c.expiry().isAfter(Instant.now()))
                .toList();
    }

    private CapabilityRecord parse(Map<String, String> item) {
        if (item.containsKey("ability")) {
            return new CapabilityRecord(
                    Ability.valueOf(item.get("ability")),
                    item.getOrDefault("resourceType", "*"),
                    parseExpiry(item.get("expiry"))
            );
        }
        // Old format: {"entityType": "resourceType", "operation": "mutate"}
        String op = item.getOrDefault("operation", "query");
        Ability ability = OPERATION_MAP.getOrDefault(op, Ability.QUERY);
        if (!OPERATION_MAP.containsKey(op)) {
            log.warn("Unknown operation '{}' in old-format capability, mapped to QUERY", op);
        }
        return new CapabilityRecord(ability, "*", null);
    }

    private Instant parseExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) return null;
        try {
            return Instant.parse(expiry);
        } catch (Exception e) {
            log.warn("Failed to parse capability expiry '{}'", expiry);
            return null;
        }
    }
}
