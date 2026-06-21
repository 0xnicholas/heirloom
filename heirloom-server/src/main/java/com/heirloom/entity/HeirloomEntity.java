package com.heirloom.entity;

import java.time.Instant;

/**
 * Common interface for all Heirloom platform entities.
 * Modeled on OpenMetadata's EntityInterface — mandatory fields
 * plus default-null optional fields that subclasses override as needed.
 */
public interface HeirloomEntity {

    // === Mandatory fields ===

    Long getId();

    /** Entity type string — matches EntityRegistry constants (e.g. "resourceType", "proposal") */
    String getEntityType();

    /** Fully Qualified Name — globally unique identifier within the platform */
    String getFullyQualifiedName();

    void setFullyQualifiedName(String fqn);

    String getName();

    String getDescription();

    /** Monotonically increasing version for optimistic concurrency control */
    Long getVersion();

    Instant getCreatedAt();

    Instant getUpdatedAt();

    // === Optional fields (default null — like OpenMetadata's EntityInterface) ===

    /** Owner reference (Actor FQN or team name). Not all entities have owners (e.g. ChangeEvent). */
    default String getOwner() {
        return null;
    }

    /** Domain namespace for multi-tenant or organizational separation */
    default String getDomain() {
        return null;
    }

    /** Content hash for incremental ingestion detection (OpenMetadata's sourceHash equivalent) */
    default String getChangeHash() {
        return null;
    }

    /** Soft-delete flag. When true, entity is excluded from default queries */
    default Boolean getDeleted() {
        return false;
    }
}
