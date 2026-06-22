package com.heirloom.knowledge.web;
import com.heirloom.auth.Authorizer;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.service.KnowledgeGraphService;
import com.heirloom.knowledge.service.KnowledgeQualityScorer;
import com.heirloom.knowledge.service.KnowledgePromotionEngine;
import com.heirloom.knowledge.service.QuerySanitizer;
import com.heirloom.web.EntityResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/v1/knowledge")
public class KnowledgeArticleResource extends EntityResource<KnowledgeArticle> {
    private final KnowledgeArticleJpaRepository jpa;
    private final KnowledgeGraphService graphService;
    private final KnowledgeQualityScorer qualityScorer;
    private final KnowledgePromotionEngine promotionEngine;
    public KnowledgeArticleResource(Authorizer a, KnowledgeArticleJpaRepository j, KnowledgeGraphService gs, KnowledgeQualityScorer qs, KnowledgePromotionEngine pe) { super(EntityRegistry.KNOWLEDGE_ARTICLE, a); jpa=j; graphService=gs; qualityScorer=qs; promotionEngine=pe; }
    @GetMapping public ResponseEntity<List<KnowledgeArticle>> list() { return ResponseEntity.ok(jpa.findAll()); }
    @GetMapping("/{id}") public ResponseEntity<KnowledgeArticle> getById(@PathVariable Long id) { return jpa.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()); }
    @GetMapping("/name/{fqn}") public ResponseEntity<KnowledgeArticle> getByFQN(@PathVariable String fqn) { return jpa.findByFullyQualifiedName(fqn).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()); }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required=false) String q,
                                     @RequestParam(required=false) String ref,
                                     @RequestParam(defaultValue="20") int limit,
                                     @RequestParam(defaultValue="0") int offset) {
        if (ref != null && !ref.isBlank()) {
            String filter = "[{\"fqn\":\"" + ref + "\"}]";
            return ResponseEntity.ok(jpa.findByEntityRef(filter));
        }
        if (q != null && !q.isBlank()) {
            String tsQuery = QuerySanitizer.toTsQuery(q);
            if (tsQuery.isEmpty()) return ResponseEntity.ok(List.of());
            return ResponseEntity.ok(jpa.search(tsQuery, limit, offset));
        }
        return ResponseEntity.badRequest().body(Map.of("error","Provide q or ref parameter"));
    }

    @GetMapping("/graph/traverse")
    public ResponseEntity<?> traverse(@RequestParam String from, @RequestParam(defaultValue="2") int maxDepth) {
        return ResponseEntity.ok(graphService.traverse(from, maxDepth));
    }

    @GetMapping("/{id}/quality")
    public ResponseEntity<?> quality(@PathVariable Long id) {
        return jpa.findById(id)
            .map(a -> ResponseEntity.ok(qualityScorer.score(a)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/promote")
    public ResponseEntity<?> promote(@RequestParam String sourceFqn) {
        return ResponseEntity.ok(promotionEngine.promote(sourceFqn));
    }
}
