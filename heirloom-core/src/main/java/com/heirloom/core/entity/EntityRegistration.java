package com.heirloom.core.entity;

/**
 * A single entry in the EntityRegistry — binds an entity type string
 * to its Java class, Repository, Service, FQN template, and API path.
 */
public record EntityRegistration(
    String entityType,
    Class<?> entityClass,
    Object repository,      // EntityRepository<?> — forward ref, typed at call site
    Object service,         // EntityService<?> — forward ref, null if not yet implemented
    String fqnTemplate,
    String collectionPath
) {}
