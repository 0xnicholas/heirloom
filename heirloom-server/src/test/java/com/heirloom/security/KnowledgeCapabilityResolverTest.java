package com.heirloom.security;

import com.heirloom.repository.RoleRepository;
import com.heirloom.security.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeCapabilityResolverTest {

    private final RoleRepository repo = mock(RoleRepository.class);
    private final KnowledgeCapabilityResolver resolver = new KnowledgeCapabilityResolver(repo);

    private static Role role(String name, String capabilities, String restrictions) {
        Role r = new Role();
        r.setName(name);
        r.setCapabilities(capabilities);
        r.setKnowledgeRestrictions(restrictions);
        return r;
    }

    @Test
    void resolveCapability_findsHighestKnowledge() {
        Role r = role("DataAnalyst",
                "[{\"entityType\":\"knowledge\",\"operation\":\"QUERY\"}," +
                " {\"entityType\":\"customer\",\"operation\":\"QUERY\"}]", null);

        assertThat(resolver.resolveCapability(r))
                .isEqualTo(KnowledgeCapability.KNOWLEDGE_QUERY);
    }

    @Test
    void resolveCapability_picksHighestAcrossMultiple() {
        Role r = role("DataSteward",
                "[{\"entityType\":\"knowledge\",\"operation\":\"QUERY\"}," +
                " {\"entityType\":\"knowledge\",\"operation\":\"CREATE\"}]", null);

        assertThat(resolver.resolveCapability(r))
                .isEqualTo(KnowledgeCapability.KNOWLEDGE_CREATE);
    }

    @Test
    void resolveCapability_acceptsPrefixedForm() {
        // Some role templates use KNOWLEDGE_QUERY (with prefix) — accept both.
        Role r = role("PowerUser",
                "[{\"entityType\":\"knowledge\",\"operation\":\"KNOWLEDGE_MANAGE\"}]", null);

        assertThat(resolver.resolveCapability(r))
                .isEqualTo(KnowledgeCapability.KNOWLEDGE_MANAGE);
    }

    @Test
    void resolveCapability_nullRole_returnsNull() {
        assertThat(resolver.resolveCapability(null)).isNull();
    }

    @Test
    void resolveCapability_noKnowledgeCap_returnsNull() {
        Role r = role("CustomerOnly",
                "[{\"entityType\":\"customer\",\"operation\":\"*\"}]", null);
        assertThat(resolver.resolveCapability(r)).isNull();
    }

    @Test
    void resolveCapability_malformedJson_returnsNull() {
        Role r = role("Broken", "not-json{[", null);
        assertThat(resolver.resolveCapability(r)).isNull();
    }

    @Test
    void resolveRestrictions_parsesAllFields() {
        Role r = role("Editor",
                "[]",
                "{\"allowedDomains\":[\"engineering\",\"data\"]," +
                " \"allowedTypes\":[\"BigQuery Table\"]," +
                " \"deniedTypes\":[\"Agent Experience Note\"]," +
                " \"maxDepth\":2," +
                " \"allowDrafts\":true}");

        KnowledgeRestrictions rs = resolver.resolveRestrictions(r);
        assertThat(rs.allowedDomains()).containsExactly("engineering", "data");
        assertThat(rs.allowedTypes()).containsExactly("BigQuery Table");
        assertThat(rs.deniedTypes()).containsExactly("Agent Experience Note");
        assertThat(rs.maxDepth()).isEqualTo(2);
        assertThat(rs.allowDrafts()).isTrue();
    }

    @Test
    void resolveRestrictions_missing_returnsNone() {
        assertThat(resolver.resolveRestrictions(null)).isEqualTo(KnowledgeRestrictions.NONE);
        Role r = role("Empty", "[]", null);
        assertThat(resolver.resolveRestrictions(r)).isEqualTo(KnowledgeRestrictions.NONE);
    }

    @Test
    void resolve_actorType_resolvesEverything() {
        Role r = role("Admin",
                "[{\"entityType\":\"knowledge\",\"operation\":\"ADMIN\"}]", null);
        when(repo.findByName("Admin")).thenReturn(Optional.of(r));

        KnowledgeCapabilityResolver.Resolution res = resolver.resolve("Admin");

        assertThat(res.capability()).isEqualTo(KnowledgeCapability.KNOWLEDGE_ADMIN);
        assertThat(res.isAdmin()).isTrue();
    }

    @Test
    void resolve_unknownActor_returnsEmptyResolution() {
        when(repo.findByName("ghost")).thenReturn(Optional.empty());

        KnowledgeCapabilityResolver.Resolution res = resolver.resolve("ghost");

        assertThat(res.capability()).isNull();
        assertThat(res.restrictions()).isEqualTo(KnowledgeRestrictions.NONE);
        assertThat(res.isAdmin()).isFalse();
    }
}