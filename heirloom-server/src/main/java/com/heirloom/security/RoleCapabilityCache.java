package com.heirloom.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.repository.RoleRepository;
import com.heirloom.security.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-through cache for {@code role name → parsed capability list}.
 *
 * <p>Phase 2.3: {@link com.heirloom.auth.RoleBasedAuthorizer} consults this cache on
 * every authorization check; cache misses fall back to {@link RoleRepository} +
 * JSON parsing. On Role create/update/delete the cache is invalidated so
 * permission changes take effect immediately.
 *
 * <p>Implementation is a {@link ConcurrentHashMap} with no TTL — correctness
 * depends entirely on the repository hooks calling {@link #invalidate(String)}
 * / {@link #invalidateAll()} after every mutation. (A TTL would be safer if
 * those hooks were ever bypassed, but the explicit-invalidation model keeps
 * semantics predictable.)
 */
@Component
public class RoleCapabilityCache {

    private static final Logger log = LoggerFactory.getLogger(RoleCapabilityCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, String>>> CAPABILITY_LIST =
            new TypeReference<>() {};

    private final RoleRepository roleRepo;
    private final ConcurrentHashMap<String, List<Map<String, String>>> cache = new ConcurrentHashMap<>();

    public RoleCapabilityCache(RoleRepository roleRepo) {
        this.roleRepo = roleRepo;
    }

    /**
     * Return the parsed capability list for a role. Cache-miss → fetch + parse +
     * store. Returns empty list when the role is missing or its capability JSON
     * fails to parse (matches the previous always-recompute behaviour).
     */
    public List<Map<String, String>> get(String roleName) {
        if (roleName == null) return List.of();
        return cache.computeIfAbsent(roleName, this::loadFromRepo);
    }

    /** Drop one role's cached capabilities (call after update/delete). */
    public void invalidate(String roleName) {
        if (roleName == null) return;
        List<Map<String, String>> previous = cache.remove(roleName);
        if (previous != null) {
            log.debug("Invalidated capability cache for role '{}'", roleName);
        }
    }

    /** Drop everything — used for safety at startup or admin recovery. */
    public void invalidateAll() {
        cache.clear();
        log.info("Invalidated entire capability cache ({} entries cleared)", cache.size());
    }

    /** Visible for tests / metrics. */
    public int size() {
        return cache.size();
    }

    private List<Map<String, String>> loadFromRepo(String roleName) {
        Optional<Role> role = roleRepo.findByName(roleName);
        if (role.isEmpty()) return List.of();
        String json = role.get().getCapabilities();
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, CAPABILITY_LIST);
        } catch (Exception e) {
            log.warn("Failed to parse capabilities for role '{}': {}", roleName, e.getMessage());
            return List.of();
        }
    }
}