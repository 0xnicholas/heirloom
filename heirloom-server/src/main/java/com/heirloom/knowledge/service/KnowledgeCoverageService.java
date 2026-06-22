package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeStatus;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.repository.TableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Phase 2.6: Knowledge Coverage view — pre-aggregated stats that answer
 * "what fraction of our resources have knowledge coverage, and where are
 * the gaps?"
 *
 * <p>Computed on demand from the underlying tables; in a high-volume
 * deployment this would be a materialised view refreshed by a scheduler.
 * For current scale the in-memory aggregation is fast and avoids the
 * Postgres-only {@code CREATE MATERIALIZED VIEW} portability trap.
 *
 * <p>Definitions:
 * <ul>
 *   <li><b>article referencing table X</b> — article whose
 *       {@code resource} field contains an {@code @} reference whose FQN
 *       matches {@code TableEntity.fullyQualifiedName}. Loose substring
 *       match; tighter matching would parse the FQN structure.</li>
 *   <li><b>coverage ratio</b> —
 *       {@code |tables with ≥1 referencing article| / |tables|}.</li>
 *   <li><b>orphan table</b> — a non-deleted TableEntity with zero
 *       referencing articles in any status.</li>
 *   <li><b>domain</b> — derived from {@code TableEntity.databaseFQN}
 *       (first segment) when present; falls back to "default".</li>
 * </ul>
 */
@Service
public class KnowledgeCoverageService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCoverageService.class);
    /** Tag character that marks an FQN reference in KnowledgeArticle.resource. */
    private static final String RESOURCE_REF_PREFIX = "@";

    private final KnowledgeArticleJpaRepository articleRepo;
    private final TableRepository tableRepo;

    public KnowledgeCoverageService(KnowledgeArticleJpaRepository articleRepo,
                                    TableRepository tableRepo) {
        this.articleRepo = articleRepo;
        this.tableRepo = tableRepo;
    }

    /** Full coverage snapshot for the {@code /v1/knowledge/coverage} endpoint. */
    public CoverageReport computeReport() {
        List<KnowledgeArticle> articles = articleRepo.findAll();
        List<TableEntity> tables = tableRepo.findAll().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getDeleted()))
                .toList();

        // Index articles by table FQN they reference — O(N) build, O(1) lookup.
        Map<String, List<KnowledgeArticle>> articlesByTable = new HashMap<>();
        for (KnowledgeArticle article : articles) {
            for (String ref : extractReferencedFqns(article)) {
                articlesByTable.computeIfAbsent(ref, k -> new ArrayList<>()).add(article);
            }
        }

        // Per-domain aggregation.
        Map<String, DomainStats> perDomain = new TreeMap<>();
        int totalTables = 0;
        int tablesWithCoverage = 0;
        List<OrphanTable> orphans = new ArrayList<>();

        for (TableEntity table : tables) {
            String domain = domainOf(table);
            DomainStats ds = perDomain.computeIfAbsent(domain, d -> new DomainStats(d));
            ds.tables++;

            List<KnowledgeArticle> refs = articlesByTable.getOrDefault(
                    table.getFullyQualifiedName(), List.of());
            ds.referencingArticles = new ArrayList<>(refs);

            if (!refs.isEmpty()) {
                tablesWithCoverage++;
                ds.tablesWithCoverage++;
            } else {
                orphans.add(new OrphanTable(
                        table.getFullyQualifiedName(),
                        table.getDatabaseServiceFQN(),
                        table.getName()));
            }
            totalTables++;
        }

        // Article-side aggregates (status, drafts, orphans).
        int published = 0, draft = 0, review = 0, archived = 0, agentCaptured = 0;
        for (KnowledgeArticle a : articles) {
            String status = a.getStatus();
            if (status == null) continue;
            switch (KnowledgeStatus.fromString(status)) {
                case PUBLISHED -> published++;
                case DRAFT -> draft++;
                case REVIEW -> review++;
                case ARCHIVED -> archived++;
            }
            if ("Agent Experience Note".equalsIgnoreCase(a.getType())) {
                agentCaptured++;
            }
        }

        double coverageRatio = totalTables == 0 ? 0.0
                : (double) tablesWithCoverage / (double) totalTables;

        List<DomainStats> domainList = new ArrayList<>(perDomain.values());
        for (DomainStats ds : domainList) {
            ds.coverageRatio = ds.tables == 0 ? 0.0
                    : (double) ds.tablesWithCoverage() / (double) ds.tables;
        }

        return new CoverageReport(
                articles.size(),
                published, draft, review, archived, agentCaptured,
                totalTables, tablesWithCoverage, coverageRatio,
                domainList, orphans);
    }

    /** Extract FQNs referenced by an article via the {@code @} convention in its resource field. */
    private static List<String> extractReferencedFqns(KnowledgeArticle article) {
        String resource = article.getResource();
        if (resource == null || resource.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        // resource may be a single @FQN or a list; split on whitespace + commas.
        for (String token : resource.split("[\\s,]+")) {
            if (token.startsWith(RESOURCE_REF_PREFIX) && token.length() > 1) {
                result.add(token.substring(1));
            }
        }
        return result;
    }

    private static String domainOf(TableEntity table) {
        String db = table.getDatabaseFQN();
        if (db == null || db.isBlank()) return "default";
        int dot = db.indexOf('.');
        return dot > 0 ? db.substring(0, dot) : db;
    }

    // === DTOs ===

    public record CoverageReport(
            int totalArticles,
            int publishedArticles,
            int draftArticles,
            int reviewArticles,
            int archivedArticles,
            int agentCapturedArticles,
            int totalTables,
            int tablesWithCoverage,
            double coverageRatio,
            List<DomainStats> perDomain,
            List<OrphanTable> orphanTables) {}

    public static final class DomainStats {
        private final String domain;
        public int tables = 0;
        public int tablesWithCoverage = 0;
        public List<KnowledgeArticle> referencingArticles = List.of();
        public double coverageRatio = 0.0;

        public DomainStats(String domain) { this.domain = domain; }
        public String domain() { return domain; }
        public int tables() { return tables; }
        public int tablesWithCoverage() { return tablesWithCoverage; }
        public int referencingArticlesCount() { return referencingArticles.size(); }
        public List<KnowledgeArticle> referencingArticles() { return referencingArticles; }
        public double coverageRatio() { return coverageRatio; }
    }

    public record OrphanTable(String fullyQualifiedName, String databaseServiceFQN, String name) {}
}