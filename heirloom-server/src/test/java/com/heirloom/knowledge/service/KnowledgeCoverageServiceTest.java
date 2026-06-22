package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.repository.TableRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeCoverageServiceTest {

    private final KnowledgeArticleJpaRepository articleRepo = mock(KnowledgeArticleJpaRepository.class);
    private final TableRepository tableRepo = mock(TableRepository.class);
    private final KnowledgeCoverageService service =
            new KnowledgeCoverageService(articleRepo, tableRepo);

    private static KnowledgeArticle article(String fqn, String type, String status, String resource) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName(fqn);
        a.setType(type);
        a.setStatus(status);
        a.setResource(resource);
        return a;
    }

    private static TableEntity table(String fqn, String dbFQN, boolean deleted) {
        TableEntity t = new TableEntity();
        t.setFullyQualifiedName(fqn);
        t.setDatabaseFQN(dbFQN);
        t.setName(fqn.substring(fqn.lastIndexOf('.') + 1));
        t.setDeleted(deleted);
        return t;
    }

    @Test
    void emptySystem_returnsZeroCoverage() {
        when(articleRepo.findAll()).thenReturn(List.of());
        when(tableRepo.findAll()).thenReturn(List.of());

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.totalArticles()).isZero();
        assertThat(report.totalTables()).isZero();
        assertThat(report.coverageRatio()).isZero();
        assertThat(report.orphanTables()).isEmpty();
        assertThat(report.perDomain()).isEmpty();
    }

    @Test
    void publishedArticles_countedCorrectly() {
        when(articleRepo.findAll()).thenReturn(List.of(
                article("k1", "BigQuery Table", "PUBLISHED", "@prod.public.t1"),
                article("k2", "BigQuery Table", "DRAFT", "@prod.public.t2"),
                article("k3", "Agent Experience Note", "DRAFT", "@prod.public.t3"),
                article("k4", "BigQuery Table", "ARCHIVED", null)));
        when(tableRepo.findAll()).thenReturn(List.of());

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.totalArticles()).isEqualTo(4);
        assertThat(report.publishedArticles()).isEqualTo(1);
        assertThat(report.draftArticles()).isEqualTo(2);
        assertThat(report.archivedArticles()).isEqualTo(1);
        assertThat(report.reviewArticles()).isZero();
        assertThat(report.agentCapturedArticles()).isEqualTo(1);
    }

    @Test
    void coverageRatio_whenAllTablesHaveArticles() {
        when(articleRepo.findAll()).thenReturn(List.of(
                article("k1", "BigQuery Table", "PUBLISHED", "@prod.public.t1"),
                article("k2", "BigQuery Table", "PUBLISHED", "@prod.public.t2")));
        when(tableRepo.findAll()).thenReturn(List.of(
                table("prod.public.t1", "prod.public", false),
                table("prod.public.t2", "prod.public", false)));

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.totalTables()).isEqualTo(2);
        assertThat(report.tablesWithCoverage()).isEqualTo(2);
        assertThat(report.coverageRatio()).isEqualTo(1.0);
        assertThat(report.orphanTables()).isEmpty();
    }

    @Test
    void coverageRatio_partialCoverage() {
        when(articleRepo.findAll()).thenReturn(List.of(
                article("k1", "BigQuery Table", "PUBLISHED", "@prod.public.t1")));
        when(tableRepo.findAll()).thenReturn(List.of(
                table("prod.public.t1", "prod.public", false),
                table("prod.public.t2", "prod.public", false),
                table("prod.public.t3", "prod.public", false),
                table("prod.public.t4", "prod.public", false)));

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.totalTables()).isEqualTo(4);
        assertThat(report.tablesWithCoverage()).isEqualTo(1);
        assertThat(report.coverageRatio()).isEqualTo(0.25);
        assertThat(report.orphanTables()).hasSize(3);
        assertThat(report.orphanTables())
                .extracting(KnowledgeCoverageService.OrphanTable::fullyQualifiedName)
                .containsExactly("prod.public.t2", "prod.public.t3", "prod.public.t4");
    }

    @Test
    void softDeletedTables_areExcludedFromCounts() {
        when(articleRepo.findAll()).thenReturn(List.of());
        when(tableRepo.findAll()).thenReturn(List.of(
                table("prod.public.active", "prod.public", false),
                table("prod.public.deleted", "prod.public", true)));

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.totalTables()).isEqualTo(1); // deleted one excluded
    }

    @Test
    void perDomain_breakdown() {
        when(articleRepo.findAll()).thenReturn(List.of(
                article("k1", "BigQuery Table", "PUBLISHED", "@prod.public.t1"),
                article("k2", "BigQuery Table", "PUBLISHED", "@sales.public.t1"),
                article("k3", "BigQuery Table", "PUBLISHED", "@sales.public.t2")));
        when(tableRepo.findAll()).thenReturn(List.of(
                table("prod.public.t1", "prod.public", false),
                table("sales.public.t1", "sales.public", false),
                table("sales.public.t2", "sales.public", false),
                table("sales.public.t3", "sales.public", false)));

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.perDomain()).hasSize(2);
        var prodStats = report.perDomain().stream()
                .filter(d -> d.domain().equals("prod")).findFirst().orElseThrow();
        var salesStats = report.perDomain().stream()
                .filter(d -> d.domain().equals("sales")).findFirst().orElseThrow();

        assertThat(prodStats.tables()).isEqualTo(1);
        assertThat(prodStats.coverageRatio()).isEqualTo(1.0);

        assertThat(salesStats.tables()).isEqualTo(3);
        assertThat(salesStats.coverageRatio()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void articleReferencingMultipleFqns_isCountedForEach() {
        when(articleRepo.findAll()).thenReturn(List.of(
                article("k1", "BigQuery Table", "PUBLISHED", "@prod.public.t1 @prod.public.t2")));
        when(tableRepo.findAll()).thenReturn(List.of(
                table("prod.public.t1", "prod.public", false),
                table("prod.public.t2", "prod.public", false)));

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.tablesWithCoverage()).isEqualTo(2);
    }

    @Test
    void articleWithNoResource_doesNotContributeToCoverage() {
        when(articleRepo.findAll()).thenReturn(List.of(
                article("k1", "BigQuery Table", "PUBLISHED", null)));
        when(tableRepo.findAll()).thenReturn(List.of(
                table("prod.public.t1", "prod.public", false)));

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.totalArticles()).isEqualTo(1);
        assertThat(report.tablesWithCoverage()).isZero();
        assertThat(report.orphanTables()).hasSize(1);
    }

    @Test
    void articleReferencingMissingTable_isNotCountedAsOrphan() {
        // The table isn't in TableEntity but the article references it.
        // Coverage only counts known tables; missing-FQN references are
        // ignored at this layer (they'd show up as orphan article refs
        // elsewhere).
        when(articleRepo.findAll()).thenReturn(List.of(
                article("k1", "BigQuery Table", "PUBLISHED", "@unknown.missing")));
        when(tableRepo.findAll()).thenReturn(List.of());

        KnowledgeCoverageService.CoverageReport report = service.computeReport();

        assertThat(report.totalTables()).isZero();
        assertThat(report.coverageRatio()).isZero();
    }
}