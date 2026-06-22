package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.security.KnowledgeCapability;
import com.heirloom.security.KnowledgeCapabilityResolver;
import com.heirloom.security.KnowledgeRestrictions;
import com.heirloom.security.KnowledgeCapabilityResolver.Resolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgePerspectiveFilterTest {

    private final KnowledgeCapabilityResolver resolver = mock(KnowledgeCapabilityResolver.class);
    private KnowledgePerspectiveFilter filter;

    @BeforeEach
    void setup() {
        filter = new KnowledgePerspectiveFilter(resolver);
    }

    private static KnowledgeArticle article(String fqn, String domain, String type, String status) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName(fqn);
        a.setDomain(domain);
        a.setType(type);
        a.setStatus(status);
        return a;
    }

    private void stubActor(String actor, KnowledgeCapability cap, KnowledgeRestrictions r) {
        when(resolver.resolve(actor)).thenReturn(new Resolution(cap, r, cap == KnowledgeCapability.KNOWLEDGE_ADMIN));
    }

    @Test
    void unknownActor_withNoCapability_seesNothing() {
        stubActor("nobody", null, KnowledgeRestrictions.NONE);

        var policy = filter.resolvePolicy("nobody");
        assertThat(policy.canRead()).isFalse();
        assertThat(filter.filterByPolicy(List.of(article("a","d","t","PUBLISHED")), policy)).isEmpty();
    }

    @Test
    void adminSeesEverythingIncludingDrafts() {
        stubActor("admin", KnowledgeCapability.KNOWLEDGE_ADMIN, KnowledgeRestrictions.NONE);

        var policy = filter.resolvePolicy("admin");
        List<KnowledgeArticle> all = List.of(
                article("a","d","t","PUBLISHED"),
                article("b","secret","Agent Experience Note","DRAFT"));
        assertThat(filter.filterByPolicy(all, policy)).hasSize(2);
        assertThat(filter.canSee(all.get(1), policy)).isTrue();
    }

    @Test
    void queryOnlyActor_cannotSeeDrafts() {
        stubActor("reader", KnowledgeCapability.KNOWLEDGE_QUERY, KnowledgeRestrictions.NONE);

        var policy = filter.resolvePolicy("reader");
        List<KnowledgeArticle> all = List.of(
                article("a","d","t","PUBLISHED"),
                article("b","d","t","DRAFT"),
                article("c","d","t","ARCHIVED"));
        List<KnowledgeArticle> visible = filter.filterByPolicy(all, policy);

        assertThat(visible).extracting(KnowledgeArticle::getFullyQualifiedName)
                .containsExactly("a", "c");
    }

    @Test
    void allowDrafts_flagExposesDrafts() {
        stubActor("editor", KnowledgeCapability.KNOWLEDGE_QUERY,
                new KnowledgeRestrictions(List.of(), List.of(), List.of(), -1, true));

        var policy = filter.resolvePolicy("editor");
        assertThat(filter.canSee(article("x","d","t","DRAFT"), policy)).isTrue();
    }

    @Test
    void allowedDomains_restrictsByDomain() {
        stubActor("eng-reader", KnowledgeCapability.KNOWLEDGE_QUERY,
                new KnowledgeRestrictions(List.of("engineering"), List.of(), List.of(), -1, false));

        var policy = filter.resolvePolicy("eng-reader");
        assertThat(filter.canSee(article("a","engineering","t","PUBLISHED"), policy)).isTrue();
        assertThat(filter.canSee(article("b","sales","t","PUBLISHED"), policy)).isFalse();
    }

    @Test
    void deniedTypes_takesPrecedence() {
        stubActor("basic", KnowledgeCapability.KNOWLEDGE_QUERY,
                new KnowledgeRestrictions(List.of(), List.of(), List.of("Agent Experience Note"), -1, false));

        var policy = filter.resolvePolicy("basic");
        assertThat(filter.canSee(article("a","d","BigQuery Table","PUBLISHED"), policy)).isTrue();
        assertThat(filter.canSee(article("b","d","Agent Experience Note","PUBLISHED"), policy)).isFalse();
    }

    @Test
    void maxDepth_zeroBlocksTraversalBeyondRoot() {
        stubActor("shallow", KnowledgeCapability.KNOWLEDGE_QUERY,
                new KnowledgeRestrictions(List.of(), List.of(), List.of(), 0, false));

        var policy = filter.resolvePolicy("shallow");
        assertThat(filter.maxDepth(policy)).isZero();
    }

    @Test
    void maxDepth_negative_meansUnlimited() {
        stubActor("deep", KnowledgeCapability.KNOWLEDGE_QUERY, KnowledgeRestrictions.NONE);

        var policy = filter.resolvePolicy("deep");
        assertThat(filter.maxDepth(policy)).isEqualTo(-1);
    }

    @Test
    void canRead_canWrite_correlateWithCapability() {
        stubActor("writer", KnowledgeCapability.KNOWLEDGE_CREATE, KnowledgeRestrictions.NONE);
        var writerPolicy = filter.resolvePolicy("writer");
        assertThat(writerPolicy.canRead()).isTrue();
        assertThat(writerPolicy.canWrite()).isTrue();

        stubActor("reader", KnowledgeCapability.KNOWLEDGE_QUERY, KnowledgeRestrictions.NONE);
        var readerPolicy = filter.resolvePolicy("reader");
        assertThat(readerPolicy.canRead()).isTrue();
        assertThat(readerPolicy.canWrite()).isFalse();
    }

    @Test
    void emptyResult_handledGracefully() {
        stubActor("reader", KnowledgeCapability.KNOWLEDGE_QUERY, KnowledgeRestrictions.NONE);
        var policy = filter.resolvePolicy("reader");
        assertThat(filter.filterByPolicy(List.of(), policy)).isEmpty();
        assertThat(filter.filterByPolicy(null, policy)).isNull();
    }
}