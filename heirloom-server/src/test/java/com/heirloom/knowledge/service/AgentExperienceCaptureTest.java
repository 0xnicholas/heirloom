package com.heirloom.knowledge.service;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.core.entity.HeirloomEntity;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentExperienceCaptureTest {

    private final KnowledgeArticleRepository articleRepo = mock(KnowledgeArticleRepository.class);
    private final AgentExperienceCapture capture = new AgentExperienceCapture(articleRepo);

    private static HeirloomEntity fakeEntity(String fqn) {
        return new HeirloomEntity() {
            @Override public Long getId() { return 1L; }
            @Override public String getEntityType() { return "table"; }
            @Override public String getFullyQualifiedName() { return fqn; }
            @Override public void setFullyQualifiedName(String f) {}
            @Override public String getName() { return fqn; }
            @Override public String getDescription() { return null; }
            @Override public Long getVersion() { return 1L; }
            @Override public java.time.Instant getCreatedAt() { return java.time.Instant.now(); }
            @Override public java.time.Instant getUpdatedAt() { return java.time.Instant.now(); }
        };
    }

    @Test
    void agentCreate_writesDraftArticle() {
        when(articleRepo.findByFilePath(anyString(), anyString())).thenReturn(Optional.empty());
        when(articleRepo.create(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean result = capture.captureIfAgent(
                fakeEntity("prod.public.customers"),
                ChangeEvent.EventType.ENTITY_CREATED,
                "agent:007");

        assertThat(result).isTrue();
        ArgumentCaptor<KnowledgeArticle> captor = ArgumentCaptor.forClass(KnowledgeArticle.class);
        verify(articleRepo).create(captor.capture());
        KnowledgeArticle draft = captor.getValue();
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getAuthor()).isEqualTo("agent:007");
        assertThat(draft.getTitle()).isEqualTo("Created: prod.public.customers");
        assertThat(draft.getBody()).contains("agent:007").contains("prod.public.customers");
        assertThat(draft.getResource()).isEqualTo("@prod.public.customers");
        assertThat(draft.getSourceFqn()).isEqualTo("agent-capture");
    }

    @Test
    void nonAgentCaller_isIgnored() {
        boolean result = capture.captureIfAgent(
                fakeEntity("prod.public.orders"),
                ChangeEvent.EventType.ENTITY_CREATED,
                "user:alice");

        assertThat(result).isFalse();
        verifyNoInteractions(articleRepo);
    }

    @Test
    void systemCaller_isIgnored() {
        boolean result = capture.captureIfAgent(
                fakeEntity("prod.public.orders"),
                ChangeEvent.EventType.ENTITY_UPDATED,
                "system");

        assertThat(result).isFalse();
        verifyNoInteractions(articleRepo);
    }

    @Test
    void nullCaller_isIgnored() {
        boolean result = capture.captureIfAgent(
                fakeEntity("prod.public.orders"),
                ChangeEvent.EventType.ENTITY_CREATED,
                null);

        assertThat(result).isFalse();
        verifyNoInteractions(articleRepo);
    }

    @Test
    void deniedEvent_isIgnored() {
        boolean result = capture.captureIfAgent(
                fakeEntity("prod.public.orders"),
                ChangeEvent.EventType.ENTITY_DENIED,
                "agent:007");

        assertThat(result).isFalse();
        verifyNoInteractions(articleRepo);
    }

    @Test
    void duplicateAgentAction_isSkipped() {
        when(articleRepo.findByFilePath(anyString(), anyString()))
                .thenReturn(Optional.of(new KnowledgeArticle()));

        boolean result = capture.captureIfAgent(
                fakeEntity("prod.public.customers"),
                ChangeEvent.EventType.ENTITY_CREATED,
                "agent:007");

        assertThat(result).isFalse();
        verify(articleRepo, never()).create(any());
    }

    @Test
    void entityWithoutFQN_isIgnored() {
        boolean result = capture.captureIfAgent(
                fakeEntity(null),
                ChangeEvent.EventType.ENTITY_CREATED,
                "agent:007");

        assertThat(result).isFalse();
        verifyNoInteractions(articleRepo);
    }

    @Test
    void updateAndDeleteEvents_bothCaptured() {
        when(articleRepo.findByFilePath(anyString(), anyString())).thenReturn(Optional.empty());
        when(articleRepo.create(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        capture.captureIfAgent(fakeEntity("prod.public.x"),
                ChangeEvent.EventType.ENTITY_UPDATED, "agent:007");
        capture.captureIfAgent(fakeEntity("prod.public.x"),
                ChangeEvent.EventType.ENTITY_DELETED, "agent:007");

        verify(articleRepo, times(2)).create(any(KnowledgeArticle.class));
    }
}