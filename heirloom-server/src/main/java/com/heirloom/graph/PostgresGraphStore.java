package com.heirloom.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PostgreSQL adjacency-table Graph Store implementation.
 * Active when {@code heirloom.graph.store-type} is {@code postgres} (default).
 */
@Component
@ConditionalOnProperty(name = "heirloom.graph.store-type", havingValue = "postgres", matchIfMissing = true)
public class PostgresGraphStore implements GraphStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresGraphStore.class);

    private final ResourceRelationshipJpaRepository repo;

    public PostgresGraphStore(ResourceRelationshipJpaRepository repo) {
        this.repo = repo;
    }

    @Override
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

    @Override
    public void removeRelationship(Long id) {
        repo.findById(id).ifPresent(entity -> {
            entity.setDeleted(true);
            repo.save(entity);
            log.info("Relationship {} deleted: {} --[{}]--> {}", id,
                entity.getSourceRid(), entity.getRelationshipType(), entity.getTargetRid());
        });
    }

    @Override
    public void removeRelationship(String sourceRid, String targetRid, String relationshipType) {
        repo.findBySourceRidAndTargetRidAndRelationshipTypeAndDeletedFalse(
            sourceRid, targetRid, relationshipType).ifPresent(entity -> {
                entity.setDeleted(true);
                repo.save(entity);
            });
    }

    @Override
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

    @Override
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

    @Override
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

    @Override
    public List<ResourceRelationshipEntity> traverseOutgoing(String rid) {
        return repo.findOutgoing(rid);
    }

    @Override
    public List<ResourceRelationshipEntity> traverseIncoming(String rid) {
        return repo.findIncoming(rid);
    }

    @Override
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

    @Override
    public String resolveUltimateOwner(String rid) {
        Set<String> visited = new HashSet<>();
        String current = rid;
        while (current != null && visited.add(current)) {
            var incomingOwnerships = repo.findByTargetRidAndSemanticsAndDeletedFalse(
                current, SEMANTICS_OWNERSHIP);
            if (incomingOwnerships.isEmpty()) {
                return current;
            }
            current = incomingOwnerships.get(0).getSourceRid();
        }
        return current;
    }

    private record BfsNode(String rid, int depth) {}
}
