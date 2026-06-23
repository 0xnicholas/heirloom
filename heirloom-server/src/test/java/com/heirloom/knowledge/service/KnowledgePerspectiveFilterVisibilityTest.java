package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.security.KnowledgeCapability;
import com.heirloom.security.KnowledgeCapabilityResolver;
import com.heirloom.security.KnowledgeCapabilityResolver.Resolution;
import com.heirloom.security.KnowledgeRestrictions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgePerspectiveFilterVisibilityTest {

    private final KnowledgeCapabilityResolver resolver = mock(KnowledgeCapabilityResolver.class);
    private final KnowledgePerspectiveFilter filter = new KnowledgePerspectiveFilter(resolver);

    private static KnowledgeArticle article(String fqn, String domain, String type, String status) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName(fqn);
        a.setDomain(domain);
        a.setType(type);
        a.setStatus(status);
        return a;
    }

    private void stub(String actor, KnowledgeCapability cap, KnowledgeRestrictions r) {
        boolean isAdmin = cap == KnowledgeCapability.KNOWLEDGE_ADMIN;
        when(resolver.resolve(actor)).thenReturn(new Resolution(cap, r, isAdmin));
    }

    @Test
    void nullArticle_isNotFound() {
        stub("anyone", KnowledgeCapability.KNOWLEDGE_QUERY, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("anyone");
        assertThat(filter.checkVisibility(null, policy)).isEqualTo(KnowledgePerspectiveFilter.Visibility.NOT_FOUND);
    }

    @Test
    void adminSeesArticle_evenDraft() {
        stub("admin", KnowledgeCapability.KNOWLEDGE_ADMIN, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("admin");
        assertThat(filter.checkVisibility(article("a","d","t","DRAFT"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.VISIBLE);
    }

    @Test
    void noReadCapability_isDenied() {
        stub("nobody", null, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("nobody");
        assertThat(filter.checkVisibility(article("a","d","t","PUBLISHED"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.DENIED);
    }

    @Test
    void draftNotAllowedForQueryOnly_isDenied() {
        stub("reader", KnowledgeCapability.KNOWLEDGE_QUERY, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("reader");
        assertThat(filter.checkVisibility(article("a","d","t","DRAFT"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.DENIED);
    }

    @Test
    void domainNotAllowed_isDenied() {
        stub("scoped", KnowledgeCapability.KNOWLEDGE_QUERY,
                new KnowledgeRestrictions(List.of("crm"), List.of(), List.of(), -1, true));
        var policy = filter.resolvePolicy("scoped");
        assertThat(filter.checkVisibility(article("a","hr","t","PUBLISHED"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.DENIED);
    }

    @Test
    void typeNotAllowed_isDenied() {
        stub("scoped", KnowledgeCapability.KNOWLEDGE_QUERY,
                new KnowledgeRestrictions(List.of(), List.of("Glossary"), List.of(), -1, true));
        var policy = filter.resolvePolicy("scoped");
        assertThat(filter.checkVisibility(article("a","d","Other","PUBLISHED"), policy))
            .isEqualTo(KnowledgePerspectiveFilter.Visibility.DENIED);
    }

    @Test
    void canSee_backCompat_delegatesToCheckVisibility() {
        stub("admin", KnowledgeCapability.KNOWLEDGE_ADMIN, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("admin");
        var a = article("a","d","t","DRAFT");
        assertThat(filter.canSee(a, policy))
            .isEqualTo(filter.checkVisibility(a, policy) == KnowledgePerspectiveFilter.Visibility.VISIBLE);
    }
}
