package com.heirloom.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.repository.RoleRepository;
import com.heirloom.security.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves the effective {@link KnowledgeCapability} and {@link KnowledgeRestrictions}
 * for an actor, given their Role.
 *
 * <p>Convention: a Role's {@code capabilities} JSON may contain entries shaped
 * like {@code {"entityType": "knowledge", "operation": "QUERY"}} — the
 * {@code operation} string is parsed as a {@link KnowledgeCapability} (after
 * stripping any {@code KNOWLEDGE_} prefix). The highest matching capability
 * wins (e.g. MANAGE implies CREATE implies QUERY).
 *
 * <p>Non-knowledge capabilities in the same Role are ignored here — they're
 * the concern of {@link com.heirloom.auth.RoleBasedAuthorizer}.
 */
@Component
public class KnowledgeCapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCapabilityResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> RESTRICTION_MAP =
            new TypeReference<>() {};

    private final RoleRepository roleRepo;

    public KnowledgeCapabilityResolver(RoleRepository roleRepo) {
        this.roleRepo = roleRepo;
    }

    /** Lookup the actor's role by name (Phase 2 convention: actor.type() == role.name). */
    public Optional<Role> findRoleFor(String actorType) {
        if (actorType == null || actorType.isBlank()) return Optional.empty();
        return roleRepo.findByName(actorType);
    }

    /**
     * Determine the effective {@link KnowledgeCapability} for a role. Returns
     * {@code null} when the role has no knowledge-scoped capability.
     */
    public KnowledgeCapability resolveCapability(Role role) {
        if (role == null) return null;
        String json = role.getCapabilities();
        if (json == null || json.isBlank()) return null;

        java.util.List<Map<String, String>> capabilities;
        try {
            capabilities = MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse capabilities JSON for role '{}': {}",
                    role.getName(), e.getMessage());
            return null;
        }

        KnowledgeCapability highest = null;
        for (Map<String, String> cap : capabilities) {
            if (!"knowledge".equals(cap.get("entityType"))) continue;
            String op = cap.get("operation");
            if (op == null) continue;
            // Accept either "QUERY" or "KNOWLEDGE_QUERY"
            String normalised = op.startsWith("KNOWLEDGE_") ? op : "KNOWLEDGE_" + op;
            KnowledgeCapability parsed;
            try {
                parsed = KnowledgeCapability.valueOf(normalised);
            } catch (IllegalArgumentException e) {
                continue; // not a knowledge capability — skip
            }
            if (highest == null || parsed.ordinal() > highest.ordinal()) {
                highest = parsed;
            }
        }
        return highest;
    }

    /** Parse the Role's knowledgeRestrictions JSONB. Returns {@link KnowledgeRestrictions#NONE} when missing/empty. */
    public KnowledgeRestrictions resolveRestrictions(Role role) {
        if (role == null) return KnowledgeRestrictions.NONE;
        String json = role.getKnowledgeRestrictions();
        if (json == null || json.isBlank()) return KnowledgeRestrictions.NONE;
        try {
            Map<String, Object> map = MAPPER.readValue(json, RESTRICTION_MAP);
            return new KnowledgeRestrictions(
                    asStringList(map.get("allowedDomains")),
                    asStringList(map.get("allowedTypes")),
                    asStringList(map.get("deniedTypes")),
                    asInt(map.get("maxDepth"), -1),
                    Boolean.TRUE.equals(map.get("allowDrafts")));
        } catch (Exception e) {
            log.warn("Failed to parse knowledgeRestrictions for role '{}': {}",
                    role.getName(), e.getMessage());
            return KnowledgeRestrictions.NONE;
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> asStringList(Object o) {
        if (o == null) return java.util.List.of();
        if (o instanceof java.util.List<?> list) {
            java.util.List<String> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        return java.util.List.of();
    }

    private static int asInt(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    /** Convenience: resolve everything for an actor in one shot. */
    public Resolution resolve(String actorType) {
        Optional<Role> roleOpt = findRoleFor(actorType);
        if (roleOpt.isEmpty()) {
            return new Resolution(null, KnowledgeRestrictions.NONE, false);
        }
        Role role = roleOpt.get();
        KnowledgeCapability cap = resolveCapability(role);
        KnowledgeRestrictions restrictions = resolveRestrictions(role);
        return new Resolution(cap, restrictions, KnowledgeCapability.KNOWLEDGE_ADMIN.equals(cap));
    }

    public record Resolution(KnowledgeCapability capability,
                             KnowledgeRestrictions restrictions,
                             boolean isAdmin) {}
}