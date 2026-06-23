package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeStatus;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import com.heirloom.repository.ProposalJpaRepository;
import com.heirloom.repository.ProposalRepository;
import com.heirloom.schema.domain.Proposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeWorkflowServiceTest {

    private KnowledgeArticleRepository articleRepo;
    private KnowledgeArticleJpaRepository articleJpa;
    private ProposalRepository proposalRepo;
    private ProposalJpaRepository proposalJpa;
    private KnowledgeWorkflowService service;

    @BeforeEach
    void setup() {
        articleRepo = mock(KnowledgeArticleRepository.class);
        articleJpa = mock(KnowledgeArticleJpaRepository.class);
        proposalRepo = mock(ProposalRepository.class);
        proposalJpa = mock(ProposalJpaRepository.class);
        service = new KnowledgeWorkflowService(articleRepo, articleJpa, proposalRepo, proposalJpa);
    }

    private static KnowledgeArticle article(long id, String fqn, String status) {
        KnowledgeArticle a = new KnowledgeArticle();
        org.springframework.test.util.ReflectionTestUtils.setField(a, "id", id);
        a.setFullyQualifiedName(fqn);
        a.setStatus(status);
        a.setName(fqn);
        return a;
    }

    @Test
    void draftToReview_isAutoApproved() {
        KnowledgeArticle a = article(1L, "crm.Customer", "DRAFT");
        when(articleJpa.findById(1L)).thenReturn(Optional.of(a));
        when(articleRepo.update(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeWorkflowService.TransitionResult result =
                service.requestTransition(1L, "REVIEW", "alice", "ready for review");

        assertThat(result.applied()).isTrue();
        assertThat(result.outcome()).isEqualTo("AUTO_APPROVED");
        assertThat(result.fromStatus()).isEqualTo(KnowledgeStatus.DRAFT);
        assertThat(result.toStatus()).isEqualTo(KnowledgeStatus.REVIEW);
        assertThat(result.proposalId()).isNull();

        ArgumentCaptor<KnowledgeArticle> captor = ArgumentCaptor.forClass(KnowledgeArticle.class);
        verify(articleRepo).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REVIEW");
        verifyNoInteractions(proposalRepo);
    }

    @Test
    void reviewToDraft_isAutoApproved() {
        KnowledgeArticle a = article(1L, "crm.Customer", "REVIEW");
        when(articleJpa.findById(1L)).thenReturn(Optional.of(a));
        when(articleRepo.update(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeWorkflowService.TransitionResult result =
                service.requestTransition(1L, "DRAFT", "alice", "more edits needed");

        assertThat(result.applied()).isTrue();
        assertThat(result.outcome()).isEqualTo("AUTO_APPROVED");
    }

    @Test
    void reviewToPublished_createsProposal() {
        KnowledgeArticle a = article(1L, "crm.Customer", "REVIEW");
        when(articleJpa.findById(1L)).thenReturn(Optional.of(a));
        when(proposalRepo.create(any(Proposal.class))).thenAnswer(inv -> {
            Proposal p = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(p, "id", 99L);
            return p;
        });

        KnowledgeWorkflowService.TransitionResult result =
                service.requestTransition(1L, "PUBLISHED", "alice", "looks good");

        assertThat(result.applied()).isFalse();
        assertThat(result.outcome()).isEqualTo("PROPOSAL_PENDING");
        assertThat(result.proposalId()).isEqualTo(99L);

        ArgumentCaptor<Proposal> captor = ArgumentCaptor.forClass(Proposal.class);
        verify(proposalRepo).create(captor.capture());
        Proposal p = captor.getValue();
        assertThat(p.getChangeType()).isEqualTo("REVIEW_KNOWLEDGE_ARTICLE");
        assertThat(p.getStatus()).isEqualTo("PENDING");
        assertThat(p.getTargetEntityType()).isEqualTo("knowledgeArticle");
        assertThat(p.getTargetEntityFQN()).isEqualTo("crm.Customer");
        assertThat(p.getDescription()).contains("REVIEW").contains("PUBLISHED").contains("looks good");
        verify(articleRepo, never()).update(any());
    }

    @Test
    void publishedToArchived_createsProposal() {
        KnowledgeArticle a = article(1L, "crm.Customer", "PUBLISHED");
        when(articleJpa.findById(1L)).thenReturn(Optional.of(a));
        when(proposalRepo.create(any(Proposal.class))).thenAnswer(inv -> {
            Proposal p = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(p, "id", 42L);
            return p;
        });

        KnowledgeWorkflowService.TransitionResult result =
                service.requestTransition(1L, "ARCHIVED", "alice", "out of date");

        assertThat(result.applied()).isFalse();
        assertThat(result.proposalId()).isEqualTo(42L);
    }

    @Test
    void invalidTransition_throwsException() {
        KnowledgeArticle a = article(1L, "crm.Customer", "DRAFT");
        when(articleJpa.findById(1L)).thenReturn(Optional.of(a));

        // DRAFT → ARCHIVED is not in validTransitions
        assertThatThrownBy(() -> service.requestTransition(1L, "ARCHIVED", "alice", "skip review"))
                .isInstanceOf(KnowledgeWorkflowService.IllegalStateTransition.class);
    }

    @Test
    void alreadyInTarget_returnsIdempotentResult() {
        KnowledgeArticle a = article(1L, "crm.Customer", "PUBLISHED");
        when(articleJpa.findById(1L)).thenReturn(Optional.of(a));

        KnowledgeWorkflowService.TransitionResult result =
                service.requestTransition(1L, "PUBLISHED", "alice", null);

        assertThat(result.outcome()).isEqualTo("ALREADY_IN_TARGET_STATUS");
        assertThat(result.applied()).isFalse();
        verifyNoInteractions(articleRepo);
        verifyNoInteractions(proposalRepo);
    }

    @Test
    void unknownArticle_throws() {
        when(articleJpa.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestTransition(404L, "REVIEW", "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("404");
    }

    @Test
    void unknownTargetStatus_throws() {
        KnowledgeArticle a = article(1L, "crm.Customer", "DRAFT");
        when(articleJpa.findById(1L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.requestTransition(1L, "garbage", "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("garbage");
    }

    @Test
    void applyApprovedProposal_reviewToPublished() {
        KnowledgeArticle a = article(1L, "crm.Customer", "REVIEW");
        when(articleJpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.of(a));

        Proposal proposal = new Proposal();
        org.springframework.test.util.ReflectionTestUtils.setField(proposal, "id", 7L);
        proposal.setStatus("APPROVED");
        proposal.setChangeType("REVIEW_KNOWLEDGE_ARTICLE");
        proposal.setTargetEntityFQN("crm.Customer");
        proposal.setName("review-crm.Customer-published");
        proposal.setDescription("promote crm.Customer from REVIEW to PUBLISHED");
        when(proposalJpa.findById(7L)).thenReturn(Optional.of(proposal));

        when(articleRepo.update(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeWorkflowService.TransitionResult result =
                service.applyApprovedProposal(7L, "reviewer:bob");

        assertThat(result.applied()).isTrue();
        assertThat(result.outcome()).isEqualTo("APPLIED");
        assertThat(result.fromStatus()).isEqualTo(KnowledgeStatus.REVIEW);
        assertThat(result.toStatus()).isEqualTo(KnowledgeStatus.PUBLISHED);

        ArgumentCaptor<KnowledgeArticle> captor = ArgumentCaptor.forClass(KnowledgeArticle.class);
        verify(articleRepo).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void applyApprovedProposal_notApproved_throws() {
        Proposal proposal = new Proposal();
        org.springframework.test.util.ReflectionTestUtils.setField(proposal, "id", 7L);
        proposal.setStatus("PENDING");
        proposal.setChangeType("REVIEW_KNOWLEDGE_ARTICLE");
        proposal.setTargetEntityFQN("crm.Customer");
        proposal.setName("review-crm.Customer-published");
        when(proposalJpa.findById(7L)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> service.applyApprovedProposal(7L, "bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not APPROVED");
    }

    @Test
    void applyApprovedProposal_wrongChangeType_throws() {
        Proposal proposal = new Proposal();
        org.springframework.test.util.ReflectionTestUtils.setField(proposal, "id", 7L);
        proposal.setStatus("APPROVED");
        proposal.setChangeType("ARCHIVE_KNOWLEDGE_ARTICLE"); // not a review
        proposal.setTargetEntityFQN("crm.Customer");
        when(proposalJpa.findById(7L)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> service.applyApprovedProposal(7L, "bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a knowledge review");
    }

    @Test
    void applyApprovedProposal_idempotent_whenAlreadyApplied() {
        KnowledgeArticle a = article(1L, "crm.Customer", "PUBLISHED");
        when(articleJpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.of(a));

        Proposal proposal = new Proposal();
        org.springframework.test.util.ReflectionTestUtils.setField(proposal, "id", 7L);
        proposal.setStatus("APPROVED");
        proposal.setChangeType("REVIEW_KNOWLEDGE_ARTICLE");
        proposal.setTargetEntityFQN("crm.Customer");
        proposal.setName("review-crm.Customer-published");
        when(proposalJpa.findById(7L)).thenReturn(Optional.of(proposal));

        KnowledgeWorkflowService.TransitionResult result =
                service.applyApprovedProposal(7L, "bob");

        assertThat(result.outcome()).isEqualTo("ALREADY_APPLIED");
        verify(articleRepo, never()).update(any());
    }
}