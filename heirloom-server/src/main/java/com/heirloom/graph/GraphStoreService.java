package com.heirloom.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Graph Store facade — delegates to the configured backend implementation.
 * Backend is selected via {@code heirloom.graph.store-type}:
 * - {@code postgres} (default): adjacency table via JPA
 * - {@code neo4j}: native graph database via Neo4j Java Driver
 */
@Service
public class GraphStoreService {

    private static final Logger log = LoggerFactory.getLogger(GraphStoreService.class);

    private final GraphStore delegate;

    public GraphStoreService(PostgresGraphStore postgres,
                              java.util.Optional<Neo4jGraphStore> neo4j) {
        if (neo4j.isPresent() && neo4j.get().isAvailable()) {
            this.delegate = neo4j.get();
            log.info("Graph Store backend: Neo4jGraphStore");
        } else {
            this.delegate = postgres;
            log.info("Graph Store backend: PostgresGraphStore");
        }
    }

    public ResourceRelationshipEntity addRelationship(String sourceRid, String targetRid,
                                                       String relationshipType, String semantics,
                                                       String createdBy) {
        return delegate.addRelationship(sourceRid, targetRid, relationshipType, semantics, createdBy);
    }

    public void removeRelationship(Long id) { delegate.removeRelationship(id); }

    public void removeRelationship(String sourceRid, String targetRid, String relationshipType) {
        delegate.removeRelationship(sourceRid, targetRid, relationshipType);
    }

    public Set<String> collectOwnedRids(String rid) { return delegate.collectOwnedRids(rid); }

    public List<String> breakReferences(String deletedRid) { return delegate.breakReferences(deletedRid); }

    public void removeAssociations(String deletedRid) { delegate.removeAssociations(deletedRid); }

    public List<ResourceRelationshipEntity> traverseOutgoing(String rid) {
        return delegate.traverseOutgoing(rid);
    }

    public List<ResourceRelationshipEntity> traverseIncoming(String rid) {
        return delegate.traverseIncoming(rid);
    }

    public List<ResourceRelationshipEntity> traverseBfs(String startRid, int maxDepth) {
        return delegate.traverseBfs(startRid, maxDepth);
    }

    public String resolveUltimateOwner(String rid) { return delegate.resolveUltimateOwner(rid); }
}
