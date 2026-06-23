package com.heirloom.knowledge.web;

import com.heirloom.auth.Authorizer;
import com.heirloom.domain.ChangeEvent;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import com.heirloom.knowledge.service.EmbeddingProvider;
import com.heirloom.knowledge.service.KnowledgeCoverageService;
import com.heirloom.knowledge.service.KnowledgeGraphService;
import com.heirloom.knowledge.service.KnowledgePerspectiveFilter;
import com.heirloom.knowledge.service.KnowledgePerspectiveFilter.AccessPolicy;
import com.heirloom.knowledge.service.KnowledgePromotionEngine;
import com.heirloom.knowledge.service.KnowledgeQualityScorer;
import com.heirloom.knowledge.service.KnowledgeWorkflowService;
import com.heirloom.knowledge.service.StaleArticleScanner;
import com.heirloom.repository.EventLogRepository;
import com.heirloom.security.KnowledgeRestrictions;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeArticleEventInstrumentationTest {

    private KnowledgeArticleJpaRepository jpa;
    private KnowledgePerspectiveFilter perspectiveFilter;
    private EventLogRepository eventLog;
    private KnowledgeGraphService graphService;
    private HttpServletRequest request;

    private KnowledgeArticleResource resource;

    @BeforeEach
    void setup() {
        // KnowledgeArticleResource extends EntityResource which reads from the
        // static EntityRegistry in its constructor. Register the type here so
        // the parent constructor can resolve it (production wires this via
        // @PostConstruct on KnowledgeArticleRepository).
        EntityRegistry.register(EntityRegistry.KNOWLEDGE_ARTICLE, KnowledgeArticle.class,
                null, null, "knowledge.{source}.{path}", "/v1/knowledge");

        Authorizer auth = mock(Authorizer.class);
        jpa = mock(KnowledgeArticleJpaRepository.class);
        perspectiveFilter = mock(KnowledgePerspectiveFilter.class);
        eventLog = mock(EventLogRepository.class);
        graphService = mock(KnowledgeGraphService.class);
        request = mock(HttpServletRequest.class);

        KnowledgeQualityScorer qs = mock(KnowledgeQualityScorer.class);
        KnowledgePromotionEngine pe = mock(KnowledgePromotionEngine.class);
        EmbeddingProvider ep = mock(EmbeddingProvider.class);
        StaleArticleScanner sas = mock(StaleArticleScanner.class);
        KnowledgeCoverageService kcs = mock(KnowledgeCoverageService.class);
        KnowledgeArticleRepository ar = mock(KnowledgeArticleRepository.class);
        KnowledgeWorkflowService wf = mock(KnowledgeWorkflowService.class);

        resource = new KnowledgeArticleResource(
            auth, jpa, graphService, qs, pe, ep, sas, perspectiveFilter,
            kcs, ar, wf, eventLog);
    }

    private static KnowledgeArticle article(String fqn, String domain, String status) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName(fqn);
        a.setDomain(domain);
        a.setStatus(status);
        return a;
    }

    private static KnowledgeArticle article(String fqn, String domain, String type, String status) {
        KnowledgeArticle a = article(fqn, domain, status);
        a.setType(type);
        return a;
    }

    private void stubPolicy(String actor, AccessPolicy policy) {
        when(perspectiveFilter.resolvePolicy(actor)).thenReturn(policy);
    }

    @Test
    void list_emitsKnowledgeSearch_withResultAndTrimmedCounts() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        when(policy.isAdmin()).thenReturn(false);
        stubPolicy("agent-007", policy);

        List<KnowledgeArticle> raw = List.of(
            article("a.1","d","PUBLISHED"),
            article("a.2","d","PUBLISHED"),
            article("a.3","d","PUBLISHED")
        );
        when(jpa.findAll()).thenReturn(raw);
        // Simulate filter trimming 1 of 3
        when(perspectiveFilter.filterByPolicy(eq(raw), eq(policy)))
            .thenReturn(List.of(raw.get(0), raw.get(1)));

        when(request.getRequestURI()).thenReturn("/v1/knowledge");
        var response = resource.list(request, null, "agent-007", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_SEARCH);
        assertThat(event.getActor()).isEqualTo("agent-007");
        assertThat(event.getDetails().get("path")).isEqualTo("/v1/knowledge");
        assertThat(((Number) event.getDetails().get("resultCount")).intValue()).isEqualTo(2);
        assertThat(((Number) event.getDetails().get("trimmedCount")).intValue()).isEqualTo(1);
    }

    @Test
    void search_emitsKnowledgeSearch_withQueryModeAndCounts() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        stubPolicy("agent-007", policy);

        when(request.getRequestURI()).thenReturn("/v1/knowledge/search");
        List<KnowledgeArticle> raw = List.of(article("a.1","d","PUBLISHED"));
        when(jpa.search(anyString(), eq(20), eq(0))).thenReturn(raw);
        when(perspectiveFilter.filterByPolicy(eq(raw), eq(policy))).thenReturn(raw);

        var response = resource.search(request, "customer churn", null, "fts", 20, 0, null, "agent-007", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_SEARCH);
        assertThat(event.getDetails().get("query")).isEqualTo("customer churn");
        assertThat(event.getDetails().get("mode")).isEqualTo("fts");
        assertThat(((Number) event.getDetails().get("resultCount")).intValue()).isEqualTo(1);
        assertThat(((Number) event.getDetails().get("trimmedCount")).intValue()).isEqualTo(0);
    }

    @Test
    void search_refParam_emitsWithRefFieldNotQuery() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        stubPolicy("agent-007", policy);

        when(request.getRequestURI()).thenReturn("/v1/knowledge/search");
        List<KnowledgeArticle> raw = List.of(article("a.1","d","PUBLISHED"));
        when(jpa.findByEntityRef(anyString())).thenReturn(raw);
        when(perspectiveFilter.filterByPolicy(eq(raw), eq(policy))).thenReturn(raw);

        var response = resource.search(request, null, "crm.Customer", "fts", 20, 0, null, "agent-007", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getDetails().get("ref")).isEqualTo("crm.Customer");
        assertThat(event.getDetails()).doesNotContainKey("query");
    }

    @Test
    void search_denied_emitsAccessDenied() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(false);
        stubPolicy("nobody", policy);
        when(request.getRequestURI()).thenReturn("/v1/knowledge/search");

        var response = resource.search(request, "x", null, "fts", 20, 0, null, "nobody", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED);
        assertThat(event.getDetails().get("reason")).isEqualTo("no_read_capability");
    }

    @Test
    void traverse_emitsKnowledgeSearch() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        when(policy.isAdmin()).thenReturn(true);
        stubPolicy("agent-007", policy);

        when(request.getRequestURI()).thenReturn("/v1/knowledge/graph/traverse");
        when(perspectiveFilter.maxDepth(policy)).thenReturn(-1);

        KnowledgeArticle a = article("a.1","d","PUBLISHED");
        KnowledgeGraphService.GraphResult graph =
            new KnowledgeGraphService.GraphResult(List.of(a), List.of(), 1);
        when(graphService.traverse("a.1", 2)).thenReturn(graph);

        var response = resource.traverse(request, "a.1", 2, null, "agent-007", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_SEARCH);
        assertThat(event.getDetails().get("path")).isEqualTo("/v1/knowledge/graph/traverse");
        assertThat(((Number) event.getDetails().get("resultCount")).intValue()).isEqualTo(1);
    }

    @Test
    void traverse_denied_emitsAccessDenied() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(false);
        stubPolicy("nobody", policy);
        when(request.getRequestURI()).thenReturn("/v1/knowledge/graph/traverse");

        var response = resource.traverse(request, "a.1", 2, null, "nobody", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED);
    }

    @Test
    void getByFQN_denied_emitsAccessDenied_withFqnAndReason() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        when(policy.isAdmin()).thenReturn(false);
        // Configure restrictions so denyReason() can identify a concrete predicate.
        // Allowed-domains does NOT include "crm", so an article in domain "crm" is denied
        // with reason "domain_not_allowed".
        when(policy.restrictions()).thenReturn(
            new KnowledgeRestrictions(List.of("sales"), null, null, -1, false));
        stubPolicy("scoped", policy);
        when(request.getRequestURI()).thenReturn("/v1/knowledge/name/crm.Customer");

        KnowledgeArticle a = article("crm.Customer","crm","Glossary","PUBLISHED");
        when(jpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.of(a));
        when(perspectiveFilter.checkVisibility(a, policy))
            .thenReturn(KnowledgePerspectiveFilter.Visibility.DENIED);

        var response = resource.getByFQN(request, "crm.Customer", null, null, "scoped");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED);
        assertThat(event.getDetails().get("fqn")).isEqualTo("crm.Customer");
        assertThat(event.getDetails().get("reason")).isIn(
            "domain_not_allowed", "type_not_allowed", "type_denied", "draft_not_allowed");
    }

    @Test
    void getByFQN_notFound_doesNotEmitEvent() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        stubPolicy("agent-007", policy);
        when(request.getRequestURI()).thenReturn("/v1/knowledge/name/crm.Customer");
        when(jpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.empty());

        var response = resource.getByFQN(request, "crm.Customer", null, null, "agent-007");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(eventLog, never()).append(any());
    }

    @Test
    void getByFQN_visible_doesNotEmitEvent() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        when(policy.isAdmin()).thenReturn(false);
        stubPolicy("admin", policy);
        when(request.getRequestURI()).thenReturn("/v1/knowledge/name/crm.Customer");

        KnowledgeArticle a = article("crm.Customer","crm","Glossary","PUBLISHED");
        when(jpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.of(a));
        when(perspectiveFilter.checkVisibility(a, policy))
            .thenReturn(KnowledgePerspectiveFilter.Visibility.VISIBLE);

        var response = resource.getByFQN(request, "crm.Customer", null, "admin", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(eventLog, never()).append(any());
    }

    @Test
    void getById_denied_emitsAccessDenied() {
        AccessPolicy policy = mock(AccessPolicy.class);
        when(policy.canRead()).thenReturn(true);
        when(policy.isAdmin()).thenReturn(false);
        // Configure restrictions so denyReason() can identify a concrete predicate.
        // Denied-types contains "Glossary", so the test article (type=Glossary) is denied
        // with reason "type_denied".
        when(policy.restrictions()).thenReturn(
            new KnowledgeRestrictions(null, null, List.of("Glossary"), -1, false));
        stubPolicy("scoped", policy);
        when(request.getRequestURI()).thenReturn("/v1/knowledge/42");

        KnowledgeArticle a = article("crm.Customer","crm","Glossary","PUBLISHED");
        when(jpa.findById(42L)).thenReturn(Optional.of(a));
        when(perspectiveFilter.checkVisibility(a, policy))
            .thenReturn(KnowledgePerspectiveFilter.Visibility.DENIED);

        var response = resource.getById(request, 42L, null, null, "scoped");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        ArgumentCaptor<ChangeEvent> captor = ArgumentCaptor.forClass(ChangeEvent.class);
        verify(eventLog).append(captor.capture());
        ChangeEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(ChangeEvent.EventType.KNOWLEDGE_ACCESS_DENIED);
        assertThat(event.getDetails().get("reason")).isIn(
            "domain_not_allowed", "type_not_allowed", "type_denied", "draft_not_allowed");
    }
}
