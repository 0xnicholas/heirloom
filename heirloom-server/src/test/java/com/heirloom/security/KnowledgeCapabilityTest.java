package com.heirloom.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCapabilityTest {

    @Test
    void queryDoesNotImplyCreate() {
        assertThat(KnowledgeCapability.implied(KnowledgeCapability.KNOWLEDGE_QUERY))
                .containsOnly(KnowledgeCapability.KNOWLEDGE_QUERY);
    }

    @Test
    void createImpliesQuery() {
        assertThat(KnowledgeCapability.covers(
                KnowledgeCapability.KNOWLEDGE_CREATE, KnowledgeCapability.KNOWLEDGE_QUERY))
                .isTrue();
    }

    @Test
    void createDoesNotImplyManage() {
        assertThat(KnowledgeCapability.covers(
                KnowledgeCapability.KNOWLEDGE_CREATE, KnowledgeCapability.KNOWLEDGE_MANAGE))
                .isFalse();
    }

    @Test
    void adminImpliesEverything() {
        for (KnowledgeCapability required : KnowledgeCapability.values()) {
            assertThat(KnowledgeCapability.covers(KnowledgeCapability.KNOWLEDGE_ADMIN, required))
                    .as("ADMIN covers %s", required)
                    .isTrue();
        }
    }

    @Test
    void nullHolderOrRequired_returnsFalse() {
        assertThat(KnowledgeCapability.covers(null, KnowledgeCapability.KNOWLEDGE_QUERY)).isFalse();
        assertThat(KnowledgeCapability.covers(KnowledgeCapability.KNOWLEDGE_ADMIN, null)).isFalse();
    }
}