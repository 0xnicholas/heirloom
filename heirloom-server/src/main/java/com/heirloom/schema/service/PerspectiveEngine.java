package com.heirloom.schema.service;

import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Phase 1.3: Generic Perspective Engine — field-level visibility for any
 * {@link ResourceType}.
 *
 * <p>Mirrors the {@code KnowledgePerspectiveFilter} model but operates on
 * logical fields declared on a Resource Type rather than Knowledge Articles.
 * The {@link ResourceType#getFieldVisibility()} JSONB map carries the
 * configuration: {@code {"salary": ["HR", "Manager"], "ssn": ["Admin"]}}.
 *
 * <p>Visibility semantics:
 * <ul>
 *   <li>A field with no entry in {@code fieldVisibility} is visible to
 *       every actor that can read the type.</li>
 *   <li>A field with an empty list ({@code []}) is hidden from everyone
 *       except {@code "*"} wildcard grants.</li>
 *   <li>A field with named roles is visible only to those roles. {@code "*"}
 *       in the list is a wildcard grant visible to all roles.</li>
 *   <li>The actor identifier is the same convention used elsewhere:
 *       {@code actor.type()} from {@link com.heirloom.auth.Authorizer.Actor}.</li>
 * </ul>
 *
 * <p>Caching is a simple in-memory map keyed by {@code typeName + "|" + actor}.
 * Cache invalidation is the responsibility of the caller (TypeRepository
 * mutations should call {@link #invalidateType(String)}; role changes would
 * call {@link #invalidateAll()}).
 */
@Component
public class PerspectiveEngine {

    private static final Logger log = LoggerFactory.getLogger(PerspectiveEngine.class);
    private static final String WILDCARD = "*";

    private final TypeRepository typeRepo;
    private final Map<String, Set<String>> visibleFieldsCache = new HashMap<>();

    public PerspectiveEngine(TypeRepository typeRepo) {
        this.typeRepo = typeRepo;
    }

    /**
     * Resolve the set of field names visible to {@code actorId} on
     * {@code typeName}. {@code actorId} is typically the actor's role name
     * (matching {@code RoleBasedAuthorizer}'s convention).
     */
    public Set<String> visibleFields(String typeName, String actorId) {
        String cacheKey = typeName + "|" + (actorId == null ? "" : actorId);
        return visibleFieldsCache.computeIfAbsent(cacheKey,
                k -> computeVisibleFields(typeName, actorId));
    }

    /** Filter a list of requested field names down to those the actor can see. */
    public List<String> filterFields(String typeName, String actorId, List<String> requested) {
        if (requested == null || requested.isEmpty()) return requested;
        Set<String> visible = visibleFields(typeName, actorId);
        List<String> filtered = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (String f : requested) {
            if (visible.contains(f)) filtered.add(f);
            else hidden.add(f);
        }
        if (!hidden.isEmpty()) {
            log.debug("Perspective engine stripped {} hidden field(s) from {} request: {}",
                    hidden.size(), typeName, hidden);
        }
        return filtered;
    }

    /** Invalidate cached visibility for one type (call after ResourceType update). */
    public void invalidateType(String typeName) {
        if (typeName == null) return;
        visibleFieldsCache.entrySet().removeIf(e -> e.getKey().startsWith(typeName + "|"));
    }

    /** Wipe the entire visibility cache. Call after a Role mutation
     *  since role→field membership may have changed. */
    public void invalidateAll() {
        visibleFieldsCache.clear();
    }

    /** Visible for tests / metrics. */
    public int cacheSize() {
        return visibleFieldsCache.size();
    }

    private Set<String> computeVisibleFields(String typeName, String actorId) {
        Optional<ResourceType> typeOpt = typeRepo.findByName(typeName);
        if (typeOpt.isEmpty()) return Set.of();

        ResourceType type = typeOpt.get();
        Map<String, List<String>> visibility = type.getFieldVisibility();
        if (visibility == null || visibility.isEmpty()) {
            // No restrictions declared — every declared field is visible.
            return type.getFields().stream().map(f -> f.name()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        String role = actorId; // convention: actor.type == role name
        Set<String> visible = new LinkedHashSet<>();
        for (var declared : type.getFields()) {
            String fieldName = declared.name();
            List<String> allowedRoles = visibility.get(fieldName);
            if (allowedRoles == null) {
                // No entry for this field → visible to anyone with read.
                visible.add(fieldName);
                continue;
            }
            if (allowedRoles.contains(WILDCARD)) {
                visible.add(fieldName);
                continue;
            }
            if (role != null && allowedRoles.contains(role)) {
                visible.add(fieldName);
            }
        }
        return visible;
    }
}