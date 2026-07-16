package com.heirloom.core.entity;

import java.time.Instant;

/**
 * Common interface for all Heirloom platform entities.
 * Modeled on OpenMetadata's EntityInterface — mandatory core fields
 * plus default-null optional fields.
 */
public interface HeirloomEntity {

    // === Mandatory ===
    Long getId();
    String getEntityType();
    String getFullyQualifiedName();
    void   setFullyQualifiedName(String fqn);
    String getName();
    String getDescription();
    Long getVersion();
    Instant getCreatedAt();
    Instant getUpdatedAt();

    // === Optional (default null) ===
    default String  getOwner()      { return null; }
    default String  getDomain()     { return null; }
    default String  getChangeHash() { return null; }
    default Boolean getDeleted()    { return false; }
}
