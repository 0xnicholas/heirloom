package com.heirloom.graph;

import com.heirloom.domain.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Graph Store Service — manages instance-level resource relationships.
 *
 * Three semantics (ADR-006):
 * - OWNERSHIP:   Lifecycle-coupled. Deleting the source cascades to owned targets.
 *                Permissions propagate from owner to owned.
 * - REFERENCE:   Weak link. Deleting the source breaks the reference (no cascade).
 * - ASSOCIATION: Loose coupling. Deleting either side removes the edge only.
 */
@Service
public class GraphStoreService {

    private static final Logger log = LoggerFactory.getLogger(GraphStoreService.class);

    public static final String SEMANTICS_OWNERSHIP = "OWNERSHIP";
    public static final String SEMANTICS_REFERENCE = "REFERENCE";
    public static final String SEMANTICS_ASSOCIATION = "ASSOCIATION";

    private final ResourceRelationshipJpaRepository repo;

    public GraphStoreService(ResourceRelationshipJpaRepository repo) {
        this.repo = repo;
    }

    // ─── Relationship CRUD ───────────────────────────────────────────────

    @Transactional
    public ResourceRelationshipEntity addRelationship(String sourceRid, String targetRid,
                                                       String relationshipType, String semantics,
                                                       String createdBy) {
        if (!List.of(SEMANTICS_OWNERSHIP, SEMANTICS_REFERENCE, SEMANTICS_ASSOCIATION)
                .contains(semantics)) {
            throw new IllegalArgumentException("invalid semantics: " + semantics);
        }

        var existing = repo.findBySourceRidAndTargetRidAndRelationshipTypeAndDeletedFalse(
            sourceRid, targetRid, relationshipType);
        if (existing.isPresent()) {
            log.debug("Relationship already exists: {} --[{}]--> {}", sourceRid, relationshipType, targetRid);
            return existing.get();
        }

        var entity = new ResourceRelationshipEntity();
        entity.setSourceRid(sourceRid);
        entity.setTargetRid(targetRid);
        entity.setRelationshipType(relationshipType);
        entity.setSemantics(semantics);
        entity.setCreatedBy(createdBy);
        repo.save(entity);
        log.info("Relationship created: {} --[{}:{}]--> {}", sourceRid, semantics, relationshipType, targetRid);
        return entity;
    }

    @Transactional
    public void removeRelationship(Long id) {
        repo.findById(id).ifPresent(entity -> {
            entity.setDeleted(true);
            repo.save(entity);
            log.info("Relationship {} deleted: {} --[{}]--> {}", id,
                entity.getSourceRid(), entity.getRelationshipType(), entity.getTargetRid());
        });
    }

    @Transactional
    public void removeRelationship(String sourceRid, String targetRid, String relationshipType) {
        repo.findBySourceRidAndTargetRidAndRelationshipTypeAndDeletedFalse(
            sourceRid, targetRid, relationshipType).ifPresent(entity -> {
                entity.setDeleted(true);
                repo.save(entity);
            });
    }

    // ─── Cascade Delete ───────────────────────────────────────────────────

    /**
     * Cascade-delete resources owned by the given RID (OWNERSHIP semantics).
     * Returns the full set of RIDs that should be deleted (including the source).
     * Does NOT delete the source RID itself — caller is responsible.
     */
    public Set<String> collectOwnedRids(String rid) {
        Set<String> visited = new LinkedHashSet<>();
        collectOwnedRecursive(rid, visited);
        return visited;
    }

    private void collectOwnedRecursive(String rid, Set<String> visited) {
        var owned = repo.findOwnedBy(rid);
        for (var edge : owned) {
            if (visited.add(edge.getTargetRid())) {
                collectOwnedRecursive(edge.getTargetRid(), visited);
            }
        }
    }

    /**
     * Break REFERENCE-type edges pointing to the deleted RID.
     * Returns the RIDs that had their references broken (for audit/logging).
     */
    public List<String> breakReferences(String deletedRid) {
        var incomingRefs = repo.findByTargetRidAndSemanticsAndDeletedFalse(deletedRid, SEMANTICS_REFERENCE);
        for (var edge : incomingRefs) {
            edge.setDeleted(true);
            repo.save(edge);
            log.info("Reference broken: {} --[{}--> {} (deleted)", edge.getSourceRid(),
                edge.getRelationshipType(), deletedRid);
        }
        return incomingRefs.stream().map(ResourceRelationshipEntity::getSourceRid).toList();
    }

    /**
     * Clean up ASSOCIATION-type edges involving the deleted RID.
     */
    public void removeAssociations(String deletedRid) {
        var edges = Stream.concat(
            repo.findBySourceRidAndSemanticsAndDeletedFalse(deletedRid, SEMANTICS_ASSOCIATION).stream(),
            repo.findByTargetRidAndSemanticsAndDeletedFalse(deletedRid, SEMANTICS_ASSOCIATION).stream()
        ).collect(Collectors.toSet());
        for (var edge : edges) {
            edge.setDeleted(true);
            repo.save(edge);
        }
        if (!edges.isEmpty()) {
            log.info("Removed {} association edges for {}", edges.size(), deletedRid);
        }
    }

    // ─── Traversal ────────────────────────────────────────────────────────

    /**
     * Traverse outgoing relationships from a given RID.
     */
    public List<ResourceRelationshipEntity> traverseOutgoing(String rid) {
        return repo.findOutgoing(rid);
    }

    /**
     * Traverse incoming relationships to a given RID.
     */
    public List<ResourceRelationshipEntity> traverseIncoming(String rid) {
        return repo.findIncoming(rid);
    }

    /**
     * Full graph traversal — BFS from a starting RID to depth N.
     * Returns a flat list of edges discovered.
     */
    public List<ResourceRelationshipEntity> traverseBfs(String startRid, int maxDepth) {
        List<ResourceRelationshipEntity> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<BfsNode> queue = new LinkedList<>();
        queue.add(new BfsNode(startRid, 0));
        visited.add(startRid);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (current.depth >= maxDepth) continue;

            var outgoing = repo.findOutgoing(current.rid);
            for (var edge : outgoing) {
                result.add(edge);
                if (visited.add(edge.getTargetRid())) {
                    queue.add(new BfsNode(edge.getTargetRid(), current.depth + 1));
                }
            }
        }

        return result;
    }

    // ─── Permission Propagation ────────────────────────────────────────────

    /**
     * Resolve the ownership chain for a given RID. Returns the ultimate owner
     * (the root of the OWNERSHIP chain), or the RID itself if it has no owner.
     */
    public String resolveUltimateOwner(String rid) {
        Set<String> visited = new HashSet<>();
        String current = rid;
        while (current != null && visited.add(current)) {
            var incomingOwnerships = repo.findByTargetRidAndSemanticsAndDeletedFalse(
                current, SEMANTICS_OWNERSHIP);
            if (incomingOwnerships.isEmpty()) {
                return current;  // no owner → ultimate owner
            }
            // Follow the first ownership edge pointing to this resource
            current = incomingOwnerships.get(0).getSourceRid();
        }
        return current;  // cycle breaker: return last seen
    }

    // ─── DTO ───────────────────────────────────────────────────────────────

    public record RelationshipDto(
        Long id, String sourceRid, String targetRid,
        String relationshipType, String semantics, String attributes,
        String createdBy
    ) {
        public static RelationshipDto from(ResourceRelationshipEntity e) {
            return new RelationshipDto(e.getId(), e.getSourceRid(), e.getTargetRid(),
                e.getRelationshipType(), e.getSemantics(), e.getAttributes(), e.getCreatedBy());
        }
    }

    private record BfsNode(String rid, int depth) {}
}
