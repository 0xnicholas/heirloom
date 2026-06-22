package com.heirloom.web;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.service.KnowledgeCoverageService;
import com.heirloom.knowledge.service.KnowledgeCoverageService.CoverageReport;
import com.heirloom.knowledge.service.KnowledgeCoverageService.OrphanTable;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.ResourceType;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 0.3: GraphQL controller — provides a single query/mutation surface
 * mounted by {@code spring-boot-starter-graphql}.
 *
 * <p>Resolvers delegate to existing services rather than reimplementing
 * business logic. The GraphQL layer is a thin protocol adapter; all data
 * access and validation lives in the underlying services.
 *
 * <p>Notable intentional gaps:
 * <ul>
 *   <li>Field-level visibility (Phase 1.3) is <b>not</b> enforced here yet —
 *       wiring through the {@code PerspectiveEngine} would require a per-
 *       resolver visibility filter. Documented as a follow-up so the
 *       surface stays small in this iteration.</li>
 *   <li>No Knowledge write mutations (createArticle, promote) — the
 *       existing REST workflow (proposals + review) is the path. GraphQL
 *       write surface is intentionally limited to {@code createResourceType}
 *       to demonstrate the resolver pipeline without duplicating the
 *       governance flow.</li>
 * </ul>
 */
@Controller
public class HeirloomGraphQLController {

    private final TypeRepository typeRepo;
    private final KnowledgeArticleJpaRepository articleRepo;
    private final KnowledgeCoverageService coverageService;

    public HeirloomGraphQLController(TypeRepository typeRepo,
                                     KnowledgeArticleJpaRepository articleRepo,
                                     KnowledgeCoverageService coverageService) {
        this.typeRepo = typeRepo;
        this.articleRepo = articleRepo;
        this.coverageService = coverageService;
    }

    // === Query resolvers ===

    @QueryMapping
    public ResourceType resourceType(@Argument String name) {
        return typeRepo.findByName(name).orElse(null);
    }

    @QueryMapping
    public List<ResourceType> resourceTypes() {
        return typeRepo.findAll().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getDeleted()))
                .toList();
    }

    @QueryMapping
    public KnowledgeArticle knowledgeArticle(@Argument String fqn) {
        return articleRepo.findByFullyQualifiedName(fqn).orElse(null);
    }

    @QueryMapping
    public List<KnowledgeArticle> knowledgeArticles(@Argument Integer limit) {
        int cap = (limit == null || limit <= 0) ? 20 : Math.min(limit, 200);
        return articleRepo.findAll().stream()
                .filter(a -> !Boolean.TRUE.equals(a.getDeleted()))
                .filter(a -> "PUBLISHED".equalsIgnoreCase(a.getStatus()))
                .limit(cap)
                .toList();
    }

    @QueryMapping
    public List<SearchHit> searchKnowledge(@Argument String query, @Argument Integer limit) {
        if (query == null || query.isBlank()) return List.of();
        int cap = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);
        // Delegate to the existing search via the REST controller's helper would
        // pull in too much coupling; do a simple substring match here so the
        // surface works without depending on the full FTS pipeline. The REST
        // /v1/knowledge/search endpoint remains the production search path.
        String lower = query.toLowerCase();
        return articleRepo.findAll().stream()
                .filter(a -> !Boolean.TRUE.equals(a.getDeleted()))
                .filter(a -> matches(a, lower))
                .limit(cap)
                .map(a -> new SearchHit(a, null))
                .toList();
    }

    @QueryMapping
    public CoverageReport knowledgeCoverage() {
        return coverageService.computeReport();
    }

    @QueryMapping
    public List<OrphanTable> orphanTables() {
        return coverageService.computeReport().orphanTables();
    }

    // === Mutation resolvers ===

    @MutationMapping
    public ResourceType createResourceType(@Argument CreateResourceTypeInput input) {
        ResourceType type = new ResourceType(input.name());
        if (input.description() != null) type.setDescription(input.description());
        if (input.domain() != null) type.setDomain(input.domain());
        return typeRepo.create(type);
    }

    // === Helpers ===

    private static boolean matches(KnowledgeArticle a, String lowerQuery) {
        return contains(a.getTitle(), lowerQuery)
                || contains(a.getDescription(), lowerQuery)
                || contains(a.getBody(), lowerQuery);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    // === DTOs ===

    public record SearchHit(KnowledgeArticle article, Double score) {}

    public record CreateResourceTypeInput(String name, String description, String domain) {}
}