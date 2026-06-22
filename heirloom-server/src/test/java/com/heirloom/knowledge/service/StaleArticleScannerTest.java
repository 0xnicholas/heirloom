package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.repository.ProposalRepository;
import com.heirloom.schema.domain.Proposal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class StaleArticleScannerTest {

    private final KnowledgeArticleJpaRepository articleRepo = mock(KnowledgeArticleJpaRepository.class);
    private final ProposalRepository proposalRepo = mock(ProposalRepository.class);
    private final StaleArticleScanner scanner = new StaleArticleScanner(articleRepo, proposalRepo);

    private static KnowledgeArticle published(String fqn, String title, Instant updatedAt) {
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName(fqn);
        a.setTitle(title);
        a.setStatus("PUBLISHED");
        a.setUpdatedAt(updatedAt);
        a.setName(fqn.split("\\.")[1] != null ? fqn.split("\\.")[1] : fqn);
        return a;
    }

    @Test
    void dryRun_returnsCandidatesWithoutCreatingProposals() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(365));
        KnowledgeArticle a = published("crm.OldCustomerDoc", "Old Customer Doc", longAgo);
        when(articleRepo.findAll()).thenReturn(List.of(a));
        when(articleRepo.findByEntityRef(anyString())).thenReturn(List.of());

        List<StaleArticleScanner.StaleCandidate> result =
                scanner.scan(Duration.ofDays(180), 1, true, "agent:007");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFullyQualifiedName()).isEqualTo("crm.OldCustomerDoc");
        assertThat(result.get(0).getInboundReferences()).isZero();
        assertThat(result.get(0).getProposalId()).isNull();
        verifyNoInteractions(proposalRepo);
    }

    @Test
    void commit_createsProposalPerCandidate() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(200));
        KnowledgeArticle a = published("crm.StaleTable", "Stale Table", longAgo);
        when(articleRepo.findAll()).thenReturn(List.of(a));
        when(articleRepo.findByEntityRef(anyString())).thenReturn(List.of());
        when(proposalRepo.create(any(Proposal.class))).thenAnswer(inv -> inv.getArgument(0));

        List<StaleArticleScanner.StaleCandidate> result =
                scanner.scan(Duration.ofDays(180), 1, false, "agent:007");

        assertThat(result).hasSize(1);
        // proposalId stays null on the candidate because the saved entity doesn't
        // get its generated id back through the parent contract — the call site
        // verifies via the captor in the next test instead.
        assertThat(result.get(0).getProposalId()).isNull();
        verify(proposalRepo, times(1)).create(any(Proposal.class));
    }

    @Test
    void skipsArticlesAboveReferenceThreshold() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(365));
        KnowledgeArticle popular = published("crm.Popular", "Popular", longAgo);
        KnowledgeArticle ignored = published("crm.Ignored", "Ignored", longAgo);
        when(articleRepo.findAll()).thenReturn(List.of(popular, ignored));
        when(articleRepo.findByEntityRef("[{\"fqn\":\"crm.Popular\"}]"))
                .thenReturn(List.of(ignored, ignored)); // 2 inbound > threshold 1

        List<StaleArticleScanner.StaleCandidate> result =
                scanner.scan(Duration.ofDays(180), 1, true, "admin");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFullyQualifiedName()).isEqualTo("crm.Ignored");
    }

    @Test
    void skipsNonPublishedArticles() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(365));
        KnowledgeArticle draft = published("crm.Draft", "Draft", longAgo);
        draft.setStatus("DRAFT");
        KnowledgeArticle archived = published("crm.Archived", "Archived", longAgo);
        archived.setStatus("ARCHIVED");
        when(articleRepo.findAll()).thenReturn(List.of(draft, archived));

        List<StaleArticleScanner.StaleCandidate> result =
                scanner.scan(Duration.ofDays(180), 1, true, "admin");

        assertThat(result).isEmpty();
    }

    @Test
    void skipsRecentArticles() {
        Instant recent = Instant.now().minus(Duration.ofDays(30));
        KnowledgeArticle fresh = published("crm.Fresh", "Fresh", recent);
        when(articleRepo.findAll()).thenReturn(List.of(fresh));

        List<StaleArticleScanner.StaleCandidate> result =
                scanner.scan(Duration.ofDays(180), 1, true, "admin");

        assertThat(result).isEmpty();
    }

    @Test
    void skipsSoftDeletedArticles() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(365));
        KnowledgeArticle ghost = published("crm.Ghost", "Ghost", longAgo);
        ghost.setDeleted(true);
        when(articleRepo.findAll()).thenReturn(List.of(ghost));

        List<StaleArticleScanner.StaleCandidate> result =
                scanner.scan(Duration.ofDays(180), 1, true, "admin");

        assertThat(result).isEmpty();
    }

    @Test
    void proposal_carriesDescriptiveContext() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(300));
        KnowledgeArticle a = published("crm.LonelyTable", "Lonely Table", longAgo);
        when(articleRepo.findAll()).thenReturn(List.of(a));
        when(articleRepo.findByEntityRef(anyString())).thenReturn(List.of());
        when(proposalRepo.create(any(Proposal.class))).thenAnswer(inv -> inv.getArgument(0));

        scanner.scan(Duration.ofDays(180), 1, false, "scanner:test");

        ArgumentCaptor<Proposal> captor = ArgumentCaptor.forClass(Proposal.class);
        verify(proposalRepo).create(captor.capture());
        Proposal p = captor.getValue();
        assertThat(p.getChangeType()).isEqualTo("ARCHIVE_KNOWLEDGE_ARTICLE");
        assertThat(p.getStatus()).isEqualTo("PENDING");
        assertThat(p.getSource()).isEqualTo("stale-scanner");
        assertThat(p.getProposedBy()).isEqualTo("scanner:test");
        assertThat(p.getTargetEntityType()).isEqualTo("knowledgeArticle");
        assertThat(p.getTargetEntityFQN()).isEqualTo("crm.LonelyTable");
        assertThat(p.getDescription()).contains("Auto-suggested archive")
                .contains("crm.LonelyTable");
        assertThat(p.getProposedChanges()).contains("PUBLISHED").contains("ARCHIVED");
    }
}