package com.heirloom.knowledge.web;

import com.heirloom.auth.Authorizer;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeArticleVersion;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.service.KnowledgeGraphService;
import com.heirloom.knowledge.service.KnowledgePerspectiveFilter;
import com.heirloom.knowledge.service.KnowledgePerspectiveFilter.AccessPolicy;
import com.heirloom.knowledge.service.KnowledgeQualityScorer;
import com.heirloom.knowledge.service.KnowledgePromotionEngine;
import com.heirloom.knowledge.service.QuerySanitizer;
import com.heirloom.knowledge.service.EmbeddingProvider;
import com.heirloom.knowledge.service.KnowledgeCoverageService;
import com.heirloom.knowledge.service.KnowledgeWorkflowService;
import com.heirloom.knowledge.service.KnowledgeWorkflowService.IllegalStateTransition;
import com.heirloom.knowledge.service.KnowledgeWorkflowService.TransitionResult;
import com.heirloom.knowledge.service.RrfScorer;
import com.heirloom.knowledge.service.StaleArticleScanner;
import com.heirloom.web.EntityResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/knowledge")
public class KnowledgeArticleResource extends EntityResource<KnowledgeArticle> {
    private final KnowledgeArticleJpaRepository jpa;
    private final com.heirloom.knowledge.repository.KnowledgeArticleRepository articleRepo;
    private final KnowledgeGraphService graphService;
    private final KnowledgeQualityScorer qualityScorer;
    private final KnowledgePromotionEngine promotionEngine;
    private final EmbeddingProvider embeddingProvider;
    private final RrfScorer rrfScorer = new RrfScorer();
    private final StaleArticleScanner staleScanner;
    private final KnowledgePerspectiveFilter perspectiveFilter;
    private final KnowledgeCoverageService coverageService;
    private final KnowledgeWorkflowService workflowService;

    public KnowledgeArticleResource(Authorizer a, KnowledgeArticleJpaRepository j,
                                    KnowledgeGraphService gs, KnowledgeQualityScorer qs,
                                    KnowledgePromotionEngine pe, EmbeddingProvider ep,
                                    StaleArticleScanner sas,
                                    KnowledgePerspectiveFilter kpf,
                                    KnowledgeCoverageService kcs,
                                    com.heirloom.knowledge.repository.KnowledgeArticleRepository ar,
                                    KnowledgeWorkflowService wf) {
        super(EntityRegistry.KNOWLEDGE_ARTICLE, a);
        jpa=j; graphService=gs; qualityScorer=qs; promotionEngine=pe;
        embeddingProvider=ep; staleScanner=sas; perspectiveFilter=kpf;
        coverageService=kcs; articleRepo=ar; workflowService=wf;
    }

    // === Read endpoints (all pass through KnowledgePerspectiveFilter) ===

    @GetMapping
    public ResponseEntity<List<KnowledgeArticle>> list(
            @RequestHeader(value = "X-Agent-Role", required = false) String role,
            @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
            @RequestHeader(value = "X-User",       required = false) String user) {
        AccessPolicy policy = resolvePolicy(role, agentId, user);
        if (!policy.canRead()) return ResponseEntity.ok(List.of());
        List<KnowledgeArticle> all = jpa.findAll();
        return ResponseEntity.ok(perspectiveFilter.filterByPolicy(all, policy));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeArticle> getById(
            @PathVariable Long id,
            @RequestHeader(value = "X-Agent-Role", required = false) String role,
            @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
            @RequestHeader(value = "X-User",       required = false) String user) {
        AccessPolicy policy = resolvePolicy(role, agentId, user);
        if (!policy.canRead()) return ResponseEntity.notFound().build();
        return jpa.findById(id)
                .filter(a -> perspectiveFilter.canSee(a, policy))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/name/{fqn}")
    public ResponseEntity<KnowledgeArticle> getByFQN(
            @PathVariable String fqn,
            @RequestHeader(value = "X-Agent-Role", required = false) String role,
            @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
            @RequestHeader(value = "X-User",       required = false) String user) {
        AccessPolicy policy = resolvePolicy(role, agentId, user);
        if (!policy.canRead()) return ResponseEntity.notFound().build();
        return jpa.findByFullyQualifiedName(fqn)
                .filter(a -> perspectiveFilter.canSee(a, policy))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(required=false) String q,
            @RequestParam(required=false) String ref,
            @RequestParam(defaultValue="fts") String mode,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int offset,
            @RequestHeader(value = "X-Agent-Role", required = false) String role,
            @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
            @RequestHeader(value = "X-User",       required = false) String user) {

        AccessPolicy policy = resolvePolicy(role, agentId, user);
        if (!policy.canRead()) return ResponseEntity.ok(List.of());

        if (ref != null && !ref.isBlank()) {
            List<KnowledgeArticle> raw = jpa.findByEntityRef("[{\"fqn\":\"" + ref + "\"}]");
            return ResponseEntity.ok(perspectiveFilter.filterByPolicy(raw, policy));
        }
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().body(Map.of("error","Provide q or ref"));

        String tsQuery = QuerySanitizer.toTsQuery(q);
        if (tsQuery.isEmpty()) return ResponseEntity.ok(List.of());

        // Determine effective mode
        String effectiveMode = mode;
        if (!"fts".equals(mode) && !embeddingProvider.isAvailable()) {
            effectiveMode = "fts";
        }

        Object rawResult = switch (effectiveMode) {
            case "vector" -> {
                float[] qe = embeddingProvider.embed(q);
                yield jpa.vectorSearch(arrayToString(qe), limit, offset);
            }
            case "hybrid" -> {
                float[] qe = embeddingProvider.embed(q);
                List<KnowledgeArticle> fts = jpa.search(tsQuery, limit * 2, 0);
                List<KnowledgeArticle> vec = jpa.vectorSearch(arrayToString(qe), limit * 2, 0);
                var fused = rrfScorer.fuse(fts, vec);
                yield fused.stream().limit(limit)
                        .map(r -> Map.of("article", r.article(), "score", r.score()))
                        .collect(Collectors.toList());
            }
            default -> jpa.search(tsQuery, limit, offset);
        };

        // Apply perspective filter on the way out.
        if (rawResult instanceof List<?> list) {
            List<KnowledgeArticle> articles = list.stream()
                    .filter(KnowledgeArticle.class::isInstance)
                    .map(KnowledgeArticle.class::cast)
                    .toList();
            List<KnowledgeArticle> filtered = perspectiveFilter.filterByPolicy(articles, policy);
            // Preserve hybrid shape (article + score map entries) when present.
            if (effectiveMode.equals("hybrid")) {
                return ResponseEntity.ok(list.stream()
                        .filter(o -> o instanceof Map<?, ?> m
                                && m.get("article") instanceof KnowledgeArticle a
                                && perspectiveFilter.canSee(a, policy))
                        .limit(limit)
                        .toList());
            }
            return ResponseEntity.ok(filtered);
        }
        return ResponseEntity.ok(rawResult);
    }

    @GetMapping("/graph/traverse")
    public ResponseEntity<?> traverse(
            @RequestParam String from,
            @RequestParam(defaultValue = "2") int maxDepth,
            @RequestHeader(value = "X-Agent-Role", required = false) String role,
            @RequestHeader(value = "X-Agent-Id",   required = false) String agentId,
            @RequestHeader(value = "X-User",       required = false) String user) {

        AccessPolicy policy = resolvePolicy(role, agentId, user);
        if (!policy.canRead()) return ResponseEntity.ok(Map.of("nodes", List.of()));

        int effectiveDepth = maxDepth;
        int cap = perspectiveFilter.maxDepth(policy);
        if (cap >= 0) effectiveDepth = Math.min(maxDepth, cap);

        return ResponseEntity.ok(graphService.traverse(from, effectiveDepth));
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

    /** Phase 4.1: request a status transition. Auto-approves safe transitions. */
    @PostMapping("/{id}/transitions")
    public ResponseEntity<?> requestTransition(
            @PathVariable Long id,
            @RequestBody TransitionRequest body,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-User", required = false) String user) {
        String caller = agentId != null ? "agent:" + agentId
                : user != null ? "user:" + user : "anonymous";
        try {
            TransitionResult result = workflowService.requestTransition(
                    id, body.targetStatus(), caller, body.comment());
            return ResponseEntity.ok(result);
        } catch (IllegalStateTransition e) {
            return ResponseEntity.status(409).body(java.util.Map.of(
                    "error", "invalid_transition",
                    "from", e.getFrom().name(),
                    "to", e.getTo().name()));
        }
    }

    /** Apply an approved review proposal — actually perform the status change. */
    @PostMapping("/{id}/transitions/{proposalId}/apply")
    public ResponseEntity<?> applyTransition(
            @PathVariable Long id,
            @PathVariable Long proposalId,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-User", required = false) String user) {
        String caller = agentId != null ? "agent:" + agentId
                : user != null ? "user:" + user : "system";
        TransitionResult result = workflowService.applyApprovedProposal(proposalId, caller);
        return ResponseEntity.ok(result);
    }

    public record TransitionRequest(String targetStatus, String comment) {}

    @PostMapping("/stale-articles/scan")
    public ResponseEntity<?> scanStaleArticles(
            @RequestParam(defaultValue = "180") int staleAfterDays,
            @RequestParam(defaultValue = "1") int maxReferences,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        var candidates = staleScanner.scan(
                java.time.Duration.ofDays(staleAfterDays),
                maxReferences,
                dryRun,
                agentId != null ? "agent:" + agentId : "user:admin");
        return ResponseEntity.ok(java.util.Map.of(
                "dryRun", dryRun,
                "staleAfterDays", staleAfterDays,
                "maxReferences", maxReferences,
                "candidateCount", candidates.size(),
                "candidates", candidates));
    }

    /**
     * Phase 2.6: coverage snapshot — total tables, articles, per-domain
     * breakdown, orphan tables. On-demand aggregation; cached at the
     * service layer if this endpoint becomes hot.
     */
    @GetMapping("/coverage")
    public ResponseEntity<KnowledgeCoverageService.CoverageReport> coverage() {
        return ResponseEntity.ok(coverageService.computeReport());
    }

    // === Phase 4.1: Knowledge version history ===

    @GetMapping("/name/{fqn}/versions")
    public ResponseEntity<List<KnowledgeArticleVersion>> listVersions(@PathVariable String fqn) {
        return ResponseEntity.ok(articleRepo.listVersions(fqn));
    }

    @GetMapping("/versions/{versionId}")
    public ResponseEntity<KnowledgeArticleVersion> getVersion(@PathVariable Long versionId) {
        return articleRepo.findVersion(versionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/name/{fqn}/restore/{versionNumber}")
    public ResponseEntity<KnowledgeArticle> restoreVersion(
            @PathVariable String fqn,
            @PathVariable int versionNumber) {
        return articleRepo.restoreVersion(fqn, versionNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // === Helpers ===

    private String arrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<arr.length;i++) { if(i>0)sb.append(","); sb.append(arr[i]); }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Resolve the AccessPolicy from headers. Order of preference:
     * {@code X-Agent-Role} (explicit role name) → {@code X-Agent-Id}
     * (treated as role name) → {@code X-User} (treated as role name) →
     * {@code "system"} (no role, falls through to default no-restriction policy).
     */
    private AccessPolicy resolvePolicy(String role, String agentId, String user) {
        String actorId = pickActor(role, agentId, user);
        return perspectiveFilter.resolvePolicy(actorId);
    }

    private static String pickActor(String role, String agentId, String user) {
        if (role != null && !role.isBlank()) return role;
        if (agentId != null && !agentId.isBlank()) return agentId;
        if (user != null && !user.isBlank()) return user;
        return "system";
    }
}