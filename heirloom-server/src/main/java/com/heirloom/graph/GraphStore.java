package com.heirloom.graph;

import java.util.List;
import java.util.Set;

/**
 * Phase 4.3: Graph Store abstraction.
 * Supports multiple backends: PostgreSQL (adjacency table) and Neo4j (native graph).
 * Backend selected via heirloom.graph.store-type configuration.
 */
public interface GraphStore {

    String SEMANTICS_OWNERSHIP = "OWNERSHIP";
    String SEMANTICS_REFERENCE = "REFERENCE";
    String SEMANTICS_ASSOCIATION = "ASSOCIATION";

    // ─── CRUD ───────────────────────────────────────────────────────────

    ResourceRelationshipEntity addRelationship(
        String sourceRid, String targetRid,
        String relationshipType, String semantics, String createdBy);

    void removeRelationship(Long id);

    void removeRelationship(String sourceRid, String targetRid, String relationshipType);

    // ─── Cascade Delete ──────────────────────────────────────────────────

    Set<String> collectOwnedRids(String rid);

    List<String> breakReferences(String deletedRid);

    void removeAssociations(String deletedRid);

    // ─── Traversal ──────────────────────────────────────────────────────

    List<ResourceRelationshipEntity> traverseOutgoing(String rid);

    List<ResourceRelationshipEntity> traverseIncoming(String rid);

    List<ResourceRelationshipEntity> traverseBfs(String startRid, int maxDepth);

    // ─── Permission Propagation ──────────────────────────────────────────

    String resolveUltimateOwner(String rid);
}
