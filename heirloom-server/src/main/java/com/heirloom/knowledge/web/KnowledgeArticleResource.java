package com.heirloom.knowledge.web;
import com.heirloom.auth.Authorizer;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.service.KnowledgeGraphService;
import com.heirloom.knowledge.service.KnowledgeQualityScorer;
import com.heirloom.knowledge.service.KnowledgePromotionEngine;
import com.heirloom.knowledge.service.QuerySanitizer;
import com.heirloom.knowledge.service.EmbeddingProvider;
import com.heirloom.knowledge.service.RrfScorer;
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
    private final EmbeddingProvider embeddingProvider;
    private final RrfScorer rrfScorer = new RrfScorer();
    public KnowledgeArticleResource(Authorizer a, KnowledgeArticleJpaRepository j, KnowledgeGraphService gs, KnowledgeQualityScorer qs, KnowledgePromotionEngine pe, EmbeddingProvider ep) { super(EntityRegistry.KNOWLEDGE_ARTICLE, a); jpa=j; graphService=gs; qualityScorer=qs; promotionEngine=pe; embeddingProvider=ep; }
    @GetMapping public ResponseEntity<List<KnowledgeArticle>> list() { return ResponseEntity.ok(jpa.findAll()); }
    @GetMapping("/{id}") public ResponseEntity<KnowledgeArticle> getById(@PathVariable Long id) { return jpa.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()); }
    @GetMapping("/name/{fqn}") public ResponseEntity<KnowledgeArticle> getByFQN(@PathVariable String fqn) { return jpa.findByFullyQualifiedName(fqn).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()); }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required=false) String q,
                                     @RequestParam(required=false) String ref,
                                     @RequestParam(defaultValue="fts") String mode,
                                     @RequestParam(defaultValue="20") int limit,
                                     @RequestParam(defaultValue="0") int offset) {
        if (ref != null && !ref.isBlank()) {
            return ResponseEntity.ok(jpa.findByEntityRef("[{\"fqn\":\"" + ref + "\"}]"));
        }
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().body(Map.of("error","Provide q or ref"));

        String tsQuery = QuerySanitizer.toTsQuery(q);
        if (tsQuery.isEmpty()) return ResponseEntity.ok(List.of());

        // Determine effective mode
        String effectiveMode = mode;
        if (!"fts".equals(mode) && !embeddingProvider.isAvailable()) {
            effectiveMode = "fts";
        }

        return switch (effectiveMode) {
            case "vector" -> {
                float[] qe = embeddingProvider.embed(q);
                yield ResponseEntity.ok(jpa.vectorSearch(arrayToString(qe), limit, offset));
            }
            case "hybrid" -> {
                float[] qe = embeddingProvider.embed(q);
                List<KnowledgeArticle> fts = jpa.search(tsQuery, limit * 2, 0);
                List<KnowledgeArticle> vec = jpa.vectorSearch(arrayToString(qe), limit * 2, 0);
                var fused = rrfScorer.fuse(fts, vec);
                yield ResponseEntity.ok(fused.stream().limit(limit).map(r -> Map.of("article",r.article(),"score",r.score())).toList());
            }
            default -> ResponseEntity.ok(jpa.search(tsQuery, limit, offset));
        };
    }

    private String arrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<arr.length;i++) { if(i>0)sb.append(","); sb.append(arr[i]); }
        sb.append("]");
        return sb.toString();
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
