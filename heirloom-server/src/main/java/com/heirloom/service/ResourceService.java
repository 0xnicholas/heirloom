package com.heirloom.service;

import com.heirloom.domain.Resource;
import com.heirloom.repository.ResourceRepository;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.*;
import com.heirloom.schema.guard.StateMachineGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;

/**
 * Business logic for Resource instances — creation, field updates, and
 * state transitions. Validates against ResourceType definitions.
 */
@Service
public class ResourceService {

    private static final Logger log = LoggerFactory.getLogger(ResourceService.class);
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RNG = new SecureRandom();

    private final ResourceRepository resourceRepo;
    private final TypeRepository typeRepo;
    private final com.heirloom.graph.GraphStoreService graphStore;

    public ResourceService(ResourceRepository resourceRepo, TypeRepository typeRepo,
                            com.heirloom.graph.GraphStoreService graphStore) {
        this.resourceRepo = resourceRepo;
        this.typeRepo = typeRepo;
        this.graphStore = graphStore;
    }

    /**
     * Create a new Resource instance. Generates RID, validates fields
     * against the ResourceType definition, and sets the initial state.
     */
    @Transactional
    public Resource create(String resourceType, String owner, Map<String, Object> fields) {
        ResourceType type = typeRepo.findByName(resourceType)
                .orElseThrow(() -> new ResourceValidationException(
                        "ResourceType '" + resourceType + "' not found in Schema Registry"));

        // Validate fields
        validateFields(type, fields);

        // Determine and validate initial state
        String initialState = type.getInitialState();
        if (initialState == null || initialState.isBlank()) {
            throw new ResourceValidationException(
                    "ResourceType '" + resourceType
                    + "' does not declare an initialState. Define one before creating resources.");
        }

        // Generate RID: {domain}.{typeName}.{8-char base62}
        String rid = type.getDomain() + "." + type.getName() + "." + randomBase62(8);

        Resource resource = new Resource(rid, resourceType, owner, initialState);
        resource.setFields(fields);
        resource.setFullyQualifiedName(rid);

        Resource saved = resourceRepo.create(resource);
        log.info("Resource created: rid={} type={} state={}", rid, resourceType, initialState);
        return saved;
    }

    /**
     * Read a Resource by RID.
     */
    @Transactional(readOnly = true)
    public Resource getByRid(String rid) {
        return resourceRepo.findByRid(rid)
                .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                .orElseThrow(() -> new ResourceValidationException(
                        "Resource not found: " + rid));
    }

    /**
     * Update fields on a Resource (partial merge). Uses optimistic locking
     * via the version column.
     */
    @Transactional
    public Resource updateFields(String rid, Map<String, Object> newFields, Long expectedVersion) {
        Resource resource = getByRid(rid);
        ResourceType type = typeRepo.findByName(resource.getResourceType())
                .orElseThrow(() -> new ResourceValidationException(
                        "ResourceType '" + resource.getResourceType() + "' not found"));

        // Optimistic lock check
        if (!resource.getVersion().equals(expectedVersion)) {
            throw new ResourceValidationException(
                    "Version conflict: expected " + expectedVersion
                    + ", current " + resource.getVersion());
        }

        // Validate new fields
        validateFields(type, newFields);

        // Merge — only update specified fields, keep others
        Map<String, Object> merged = new LinkedHashMap<>(resource.getFields());
        merged.putAll(newFields);
        resource.setFields(merged);

        Resource updated = resourceRepo.update(resource);
        log.info("Resource fields updated: rid={} fields={}", rid, newFields.keySet());
        return updated;
    }

    /**
     * Transition a Resource to a new state. Validates the transition
     * against the ResourceType's state machine.
     */
    @Transactional
    public Resource transitionState(String rid, String targetState) {
        Resource resource = getByRid(rid);
        ResourceType type = typeRepo.findByName(resource.getResourceType())
                .orElseThrow(() -> new ResourceValidationException(
                        "ResourceType '" + resource.getResourceType() + "' not found"));

        String current = resource.getCurrentState();
        if (current == null) {
            current = type.getInitialState();
        }
        final String currentState = current;

        // Check the state machine for this transition
        boolean legal = StateMachine.isValidTransition(type, currentState, targetState);
        if (!legal) {
            throw new ResourceValidationException(
                    "Illegal state transition '" + currentState + " → " + targetState
                    + "' for type '" + type.getName()
                    + "'. Valid from '" + currentState + "': "
                    + StateMachine.transitionsFrom(type, currentState));
        }

        resource.setCurrentState(targetState);
        Resource updated = resourceRepo.update(resource);
        log.info("Resource state transitioned: rid={} {}→{}", rid, currentState, targetState);
        return updated;
    }

    /**
     * List resources with optional filters.
     */
    @Transactional(readOnly = true)
    public List<Resource> list(String type, String state, Map<String, String> fieldFilters,
                                int limit, int offset) {
        return resourceRepo.list(type, state, fieldFilters, limit, offset);
    }

    @Transactional(readOnly = true)
    public long count(String type, String state) {
        return resourceRepo.count(type, state);
    }

    // === CDC methods ===

    /**
     * Create a Resource with a caller-provided RID (for CDC deterministic RID generation).
     * Shares field validation and initialState logic with {@link #create}.
     * If a Resource with the same RID already exists, returns the existing one (idempotent).
     */
    @Transactional
    public Resource createWithRid(String rid, String resourceType, String owner,
                                   Map<String, Object> fields) {
        // Idempotent: if RID already exists, skip
        var existing = resourceRepo.findByRid(rid);
        if (existing.isPresent() && !Boolean.TRUE.equals(existing.get().getDeleted())) {
            return existing.get();
        }

        ResourceType type = typeRepo.findByName(resourceType)
                .orElseThrow(() -> new ResourceValidationException(
                        "ResourceType '" + resourceType + "' not found"));

        validateFields(type, fields);

        String initialState = type.getInitialState();
        if (initialState == null || initialState.isBlank()) {
            throw new ResourceValidationException(
                    "ResourceType '" + resourceType + "' does not declare an initialState");
        }

        Resource resource = new Resource(rid, resourceType, owner, initialState);
        resource.setFields(fields);
        resource.setFullyQualifiedName(rid);

        Resource saved = resourceRepo.create(resource);
        log.info("Resource created (CDC): rid={} type={}", rid, resourceType);
        return saved;
    }

    /**
     * Update fields bypassing optimistic lock (for CDC).
     * Executes native SQL UPDATE — does not use entityManager.merge().
     */
    @Transactional
    public void cdcUpdateFields(String rid, Map<String, Object> fields) {
        Resource resource = getByRid(rid);
        if (fields == null || fields.isEmpty()) return;

        Map<String, Object> merged = new LinkedHashMap<>(resource.getFields());
        merged.putAll(fields);

        // Direct JPA save with @Version field — OK for CDC since we're the
        // only writer after initial sync. If concurrent writes become an issue,
        // switch to native SQL UPDATE.
        resource.setFields(merged);
        resourceRepo.update(resource);
        log.debug("Resource fields updated (CDC): rid={} fields={}", rid, fields.keySet());
    }

    /**
     * Soft-delete a Resource via the business layer.
     * Checks that the ResourceType declares the DROP ability.
     * Cascades to owned resources (OWNERSHIP semantics), breaks incoming
     * REFERENCE edges, and removes ASSOCIATION edges.
     */
    @Transactional
    public void markDeleted(String rid) {
        Resource resource = getByRid(rid);
        ResourceType type = typeRepo.findByName(resource.getResourceType())
                .orElseThrow(() -> new ResourceValidationException(
                        "ResourceType '" + resource.getResourceType() + "' not found"));

        if (!type.getAbilities().contains(Ability.DROP)) {
            throw new ResourceValidationException(
                    "Cannot delete resource of type '" + type.getName()
                    + "': type does not declare DROP ability");
        }

        // Phase 2.5: Graph Store cascade
        // 1. Collect and delete owned resources (OWNERSHIP)
        var ownedRids = graphStore.collectOwnedRids(rid);
        for (var ownedRid : ownedRids) {
            var owned = resourceRepo.findByRid(ownedRid).orElse(null);
            if (owned != null && !Boolean.TRUE.equals(owned.getDeleted())) {
                owned.setDeleted(true);
                resourceRepo.update(owned);
                log.info("Cascade delete owned resource: rid={} (owned by {})", ownedRid, rid);
            }
        }

        // 2. Break incoming REFERENCE edges
        graphStore.breakReferences(rid);

        // 3. Remove ASSOCIATION edges
        graphStore.removeAssociations(rid);

        // 4. Mark the resource itself deleted
        resource.setDeleted(true);
        resourceRepo.update(resource);
        log.info("Resource marked deleted with cascade: rid={} ({} owned cascaded)",
            rid, ownedRids.size());
    }

    // --- Private helpers ---

    private void validateFields(ResourceType type, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return;

        Set<String> declaredNames = new HashSet<>();
        Map<String, FieldType> declaredTypes = new LinkedHashMap<>();
        for (Field f : type.getFields()) {
            declaredNames.add(f.name());
            declaredTypes.put(f.name(), f.type());
        }

        for (var entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            Object value = entry.getValue();

            if (!declaredNames.contains(fieldName)) {
                throw new ResourceValidationException(
                        "Field '" + fieldName + "' is not declared on type '"
                        + type.getName() + "'. Declared fields: " + declaredNames);
            }

            FieldType expectedType = declaredTypes.get(fieldName);
            if (!isTypeCompatible(expectedType, value)) {
                throw new ResourceValidationException(
                        "Field '" + fieldName + "' type mismatch: expected "
                        + expectedType + ", got " + value.getClass().getSimpleName());
            }
        }
    }

    private boolean isTypeCompatible(FieldType expected, Object value) {
        if (value == null) return true;
        return switch (expected) {
            case STRING, DATE, TIMESTAMP, RID -> value instanceof String;
            case NUMBER  -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case ENUM    -> value instanceof String || value instanceof List;
        };
    }

    private static String randomBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(RNG.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
