package com.heirloom.web;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.service.KnowledgeCoverageService;
import com.heirloom.knowledge.service.KnowledgeCoverageService.CoverageReport;
import com.heirloom.knowledge.service.KnowledgeCoverageService.OrphanTable;
import com.heirloom.repository.TypeRepository;
import com.heirloom.schema.domain.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeirloomGraphQLControllerTest {

    private TypeRepository typeRepo;
    private KnowledgeArticleJpaRepository articleRepo;
    private KnowledgeCoverageService coverageService;
    private HeirloomGraphQLController controller;

    @BeforeEach
    void setup() {
        typeRepo = mock(TypeRepository.class);
        articleRepo = mock(KnowledgeArticleJpaRepository.class);
        coverageService = mock(KnowledgeCoverageService.class);
        controller = new HeirloomGraphQLController(typeRepo, articleRepo, coverageService);
    }

    private static ResourceType resourceType(String name) {
        ResourceType t = new ResourceType(name);
        t.setDomain("default");
        t.setFullyQualifiedName("default." + name);
        // Avoid JPA @Version defaulting to 0; the controller exposes version.
        ReflectionTestUtils.setField(t, "version", 3);
        return t;
    }

    private static KnowledgeArticle article(String fqn, String title, String status) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName(fqn);
        a.setTitle(title);
        a.setStatus(status);
        a.setDomain("default");
        a.setType("BigQuery Table");
        a.setDeleted(false);
        return a;
    }

    @Test
    void resourceType_returnsMatch() {
        when(typeRepo.findByName("Customer"))
                .thenReturn(Optional.of(resourceType("Customer")));

        ResourceType result = controller.resourceType("Customer");
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Customer");
    }

    @Test
    void resourceType_returnsNullWhenAbsent() {
        when(typeRepo.findByName("ghost")).thenReturn(Optional.empty());

        assertThat(controller.resourceType("ghost")).isNull();
    }

    @Test
    void resourceTypes_filtersDeleted() {
        ResourceType live = resourceType("Customer");
        ResourceType dead = resourceType("Old");
        dead.setDeleted(true);
        when(typeRepo.findAll()).thenReturn(List.of(live, dead));

        List<ResourceType> result = controller.resourceTypes();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Customer");
    }

    @Test
    void knowledgeArticle_lookupByFQN() {
        when(articleRepo.findByFullyQualifiedName("crm.Customer"))
                .thenReturn(Optional.of(article("crm.Customer", "Customer", "PUBLISHED")));

        KnowledgeArticle result = controller.knowledgeArticle("crm.Customer");
        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Customer");
    }

    @Test
    void knowledgeArticles_returnsOnlyPublishedAndCapsLimit() {
        when(articleRepo.findAll()).thenReturn(List.of(
                article("a1", "A1", "PUBLISHED"),
                article("a2", "A2", "DRAFT"),
                article("a3", "A3", "PUBLISHED"),
                article("a4", "A4", "ARCHIVED")));

        List<KnowledgeArticle> result = controller.knowledgeArticles(10);
        assertThat(result).hasSize(2);
        assertThat(result).extracting(KnowledgeArticle::getFullyQualifiedName)
                .containsExactly("a1", "a3");
    }

    @Test
    void knowledgeArticles_limitClampedAt200() {
        // Build many articles, request a huge limit — should clamp.
        List<KnowledgeArticle> many = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add(article("k" + i, "K" + i, "PUBLISHED"));
        }
        when(articleRepo.findAll()).thenReturn(many);

        List<KnowledgeArticle> result = controller.knowledgeArticles(10000);
        assertThat(result).hasSize(200);
    }

    @Test
    void searchKnowledge_blankQuery_returnsEmpty() {
        assertThat(controller.searchKnowledge("", 10)).isEmpty();
        assertThat(controller.searchKnowledge(null, 10)).isEmpty();
    }

    @Test
    void searchKnowledge_matchesTitleDescriptionBody() {
        KnowledgeArticle k1 = article("k1", "Customer Records", "PUBLISHED");
        KnowledgeArticle k2 = article("k2", "Other",         "PUBLISHED");
        k2.setBody("mentions customer service");
        KnowledgeArticle k3 = article("k3", "Unrelated",      "PUBLISHED");
        KnowledgeArticle k4 = article("k4", "Empty",          "PUBLISHED");
        k4.setDescription("customer-facing");
        when(articleRepo.findAll()).thenReturn(List.of(k1, k2, k3, k4));

        List<HeirloomGraphQLController.SearchHit> result =
                controller.searchKnowledge("customer", 10);

        assertThat(result).extracting(h -> h.article().getFullyQualifiedName())
                .containsExactlyInAnyOrder("k1", "k2", "k4");
    }

    @Test
    void searchKnowledge_limitClamped() {
        when(articleRepo.findAll()).thenReturn(List.of(
                article("a", "x customer", "PUBLISHED"),
                article("b", "x customer", "PUBLISHED")));

        assertThat(controller.searchKnowledge("customer", 1)).hasSize(1);
    }

    @Test
    void knowledgeCoverage_delegatesToService() {
        CoverageReport report = new CoverageReport(
                10, 7, 2, 1, 0, 1,
                20, 15, 0.75,
                List.of(), List.of(new OrphanTable("x.y.z", null, "z")));
        when(coverageService.computeReport()).thenReturn(report);

        CoverageReport result = controller.knowledgeCoverage();
        assertThat(result.totalArticles()).isEqualTo(10);
        assertThat(result.coverageRatio()).isEqualTo(0.75);
    }

    @Test
    void orphanTables_delegates() {
        when(coverageService.computeReport()).thenReturn(new CoverageReport(
                0, 0, 0, 0, 0, 0,
                5, 2, 0.4,
                List.of(),
                List.of(new OrphanTable("a.b.c1", null, "c1"),
                        new OrphanTable("a.b.c2", null, "c2"))));

        List<OrphanTable> orphans = controller.orphanTables();
        assertThat(orphans).hasSize(2);
        assertThat(orphans).extracting(OrphanTable::name).containsExactly("c1", "c2");
    }

    @Test
    void createResourceType_persists() {
        ResourceType saved = resourceType("NewType");
        when(typeRepo.create(any(ResourceType.class))).thenReturn(saved);

        ResourceType result = controller.createResourceType(
                new HeirloomGraphQLController.CreateResourceTypeInput(
                        "NewType", "A description", "engineering"));

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("NewType");
    }
}