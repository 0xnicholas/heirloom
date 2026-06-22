package com.heirloom.entity;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central entity type registry — modeled on OpenMetadata's {@code Entity.java}.
 * <p>
 * All Heirloom entity types (both metadata and semantic) are registered here
 * with their Java class, Repository, Service, FQN template, and API path.
 * Registration is decentralized: each {@code @Repository} bean calls
 * {@link #register} in its {@code @PostConstruct}.
 */
@Component
public class EntityRegistry {

    // === Entity type constants ===

    // Metadata layer (OpenMetadata parity)
    public static final String DATABASE_SERVICE = "databaseService";
    public static final String DATABASE         = "database";
    public static final String DATABASE_SCHEMA  = "databaseSchema";
    public static final String TABLE            = "table";
    public static final String LINEAGE          = "lineage";
    public static final String ROLE             = "role";
    public static final String ACTION           = "action";
    public static final String FUNCTION         = "function";
    public static final String CAPABILITY       = "capability";

    // Semantic layer (Heirloom unique)
    public static final String RESOURCE_TYPE    = "resourceType";
    public static final String PROPOSAL         = "proposal";
    public static final String MAPPING_RULE     = "mappingRule";

    // Platform layer
    public static final String DISCOVERY_SOURCE = "discoverySource";
    public static final String DISCOVERY_REPORT = "discoveryReport";
    public static final String EVENT            = "event";

    // === Common field constants (avoid hardcoded strings) ===
    public static final String FIELD_OWNER       = "owner";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_FQN         = "fullyQualifiedName";
    public static final String FIELD_VERSION     = "version";
    public static final String FQN_SEPARATOR     = ".";

    // === Thread-safe static registry ===
    private static final Map<String, EntityRegistration> registry = new ConcurrentHashMap<>();

    /**
     * Called by each Repository bean in its {@code @PostConstruct}.
     * Decentralized registration — new entity types don't require changes here.
     */
    public static void register(String entityType, Class<?> entityClass,
                                 Object repository, Object service,
                                 String fqnTemplate, String collectionPath) {
        registry.put(entityType, new EntityRegistration(
            entityType, entityClass, repository, service, fqnTemplate, collectionPath));
    }

    // === Query methods ===

    @SuppressWarnings("unchecked")
    public static <T> T getRepository(String entityType) {
        EntityRegistration reg = registry.get(entityType);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown entity type: " + entityType);
        }
        return (T) reg.repository();
    }

    @SuppressWarnings("unchecked")
    public static <T> T getService(String entityType) {
        EntityRegistration reg = registry.get(entityType);
        return reg != null ? (T) reg.service() : null;
    }

    public static Class<?> getEntityClass(String entityType) {
        EntityRegistration reg = registry.get(entityType);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown entity type: " + entityType);
        }
        return reg.entityClass();
    }

    public static Set<String> getAllEntityTypes() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    /** For testing — clears the registry between tests. */
    public static void clear() {
        registry.clear();
    }
}
