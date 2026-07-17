package com.heirloom.graph.web;

import com.heirloom.graph.GraphStoreService;
import com.heirloom.graph.RelationshipDto;
import com.heirloom.graph.ResourceRelationshipEntity;
import com.heirloom.graph.ResourceRelationshipJpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Phase 2.5: Graph Store REST API.
 * Manages instance-level resource relationships and graph traversal.
 */
@RestController
@RequestMapping("/v1/graph")
public class GraphResource {

    private final GraphStoreService graphStore;
    private final ResourceRelationshipJpaRepository repo;

    public GraphResource(GraphStoreService graphStore,
                          ResourceRelationshipJpaRepository repo) {
        this.graphStore = graphStore;
        this.repo = repo;
    }

    // ─── Relationship CRUD ───────────────────────────────────────────────

    @PostMapping("/relationships")
    public ResponseEntity<RelationshipDto> createRelationship(
            @RequestBody CreateRelationshipRequest req) {
        var entity = graphStore.addRelationship(
            req.sourceRid(), req.targetRid(),
            req.relationshipType(), req.semantics(), req.createdBy());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(RelationshipDto.from(entity));
    }

    @DeleteMapping("/relationships/{id}")
    public ResponseEntity<Void> deleteRelationship(@PathVariable Long id) {
        graphStore.removeRelationship(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/relationships/{id}")
    public ResponseEntity<RelationshipDto> getRelationship(@PathVariable Long id) {
        return repo.findById(id)
            .filter(e -> !Boolean.TRUE.equals(e.getDeleted()))
            .map(e -> ResponseEntity.ok(RelationshipDto.from(e)))
            .orElse(ResponseEntity.notFound().build());
    }

    // ─── Traversal ───────────────────────────────────────────────────────

    @GetMapping("/outgoing/{rid}")
    public List<RelationshipDto> outgoing(@PathVariable String rid) {
        return graphStore.traverseOutgoing(rid).stream()
            .map(RelationshipDto::from)
            .toList();
    }

    @GetMapping("/incoming/{rid}")
    public List<RelationshipDto> incoming(@PathVariable String rid) {
        return graphStore.traverseIncoming(rid).stream()
            .map(RelationshipDto::from)
            .toList();
    }

    @GetMapping("/traverse/{rid}")
    public List<RelationshipDto> traverse(
            @PathVariable String rid,
            @RequestParam(defaultValue = "3") int depth) {
        return graphStore.traverseBfs(rid, Math.min(depth, 10)).stream()
            .map(RelationshipDto::from)
            .toList();
    }

    // ─── Ownership ───────────────────────────────────────────────────────

    @GetMapping("/owner/{rid}")
    public ResponseEntity<Map<String, String>> ultimateOwner(@PathVariable String rid) {
        var owner = graphStore.resolveUltimateOwner(rid);
        return ResponseEntity.ok(Map.of("rid", rid, "ultimateOwner", owner));
    }

    // ─── DTO ─────────────────────────────────────────────────────────────

    public record CreateRelationshipRequest(
        String sourceRid, String targetRid,
        String relationshipType, String semantics, String createdBy
    ) {}
}
