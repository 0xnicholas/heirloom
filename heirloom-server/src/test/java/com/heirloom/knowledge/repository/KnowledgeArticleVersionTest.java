package com.heirloom.knowledge.repository;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeArticleVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit-level tests for the version-snapshot logic in KnowledgeArticleRepository.
 * Mocks the two underlying JPA repositories so we can verify what the wrapper
 * saves without standing up a full Spring context.
 */
class KnowledgeArticleVersionTest {

    private KnowledgeArticleJpaRepository jpa;
    private KnowledgeArticleVersionJpaRepository versionJpa;
    private KnowledgeArticleRepository repo;

    @BeforeEach
    void setup() {
        jpa = mock(KnowledgeArticleJpaRepository.class);
        versionJpa = mock(KnowledgeArticleVersionJpaRepository.class);
        repo = new KnowledgeArticleRepository(jpa, versionJpa);
    }

    private static KnowledgeArticle article(long id, String fqn, String title, String body) {
        KnowledgeArticle a = new KnowledgeArticle();
        ReflectionTestUtils.setField(a, "id", id);
        a.setFullyQualifiedName(fqn);
        a.setTitle(title);
        a.setBody(body);
        a.setStatus("PUBLISHED");
        a.setType("BigQuery Table");
        a.setDomain("default");
        a.setFilePath(fqn + ".md");
        a.setVersion(1L);
        return a;
    }

    @Test
    void update_capturesSnapshotOfExistingState_beforeMutation() {
        KnowledgeArticle persisted = article(7L, "crm.Customer", "Old Title", "old body");
        persisted.setVersion(3L);
        when(jpa.findById(7L)).thenReturn(Optional.of(persisted));
        when(versionJpa.findTopByArticleFqnOrderByVersionNumberDesc("crm.Customer"))
                .thenReturn(Optional.empty());
        // The doUpdate chain ends at jpa.save; just return the same entity.
        when(jpa.save(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeArticle incoming = article(7L, "crm.Customer", "New Title", "new body");
        KnowledgeArticle result = repo.update(incoming);

        // The snapshot captured the OLD state, not the incoming new state.
        ArgumentCaptor<KnowledgeArticleVersion> captor =
                ArgumentCaptor.forClass(KnowledgeArticleVersion.class);
        verify(versionJpa).save(captor.capture());
        KnowledgeArticleVersion snap = captor.getValue();
        assertThat(snap.getArticleId()).isEqualTo(7L);
        assertThat(snap.getArticleFqn()).isEqualTo("crm.Customer");
        assertThat(snap.getTitle()).isEqualTo("Old Title");
        assertThat(snap.getBody()).isEqualTo("old body");
        assertThat(snap.getStatus()).isEqualTo("PUBLISHED");
        assertThat(snap.getVersion()).isEqualTo(3L);
        assertThat(snap.getSnapshotReason()).isEqualTo("update");
        assertThat(snap.getVersionNumber()).isEqualTo(1);
        assertThat(snap.getSnapshotAt()).isNotNull();

        // The returned article reflects the new state.
        assertThat(result.getTitle()).isEqualTo("New Title");
        assertThat(result.getBody()).isEqualTo("new body");
    }

    @Test
    void update_incrementsVersionNumberPerCall() {
        when(jpa.findById(7L)).thenReturn(Optional.of(article(7L, "crm.Customer", "t", "b")));
        when(versionJpa.findTopByArticleFqnOrderByVersionNumberDesc("crm.Customer"))
                .thenReturn(Optional.of(versionSnap("crm.Customer", 5)));
        when(jpa.save(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        repo.update(article(7L, "crm.Customer", "t2", "b2"));

        ArgumentCaptor<KnowledgeArticleVersion> captor =
                ArgumentCaptor.forClass(KnowledgeArticleVersion.class);
        verify(versionJpa).save(captor.capture());
        assertThat(captor.getValue().getVersionNumber()).isEqualTo(6);
    }

    @Test
    void update_withoutExistingRow_skipsSnapshot() {
        // findById returns empty → repository can't snapshot anything.
        when(jpa.findById(404L)).thenReturn(Optional.empty());
        when(jpa.save(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeArticle incoming = article(404L, "crm.New", "t", "b");
        repo.update(incoming);

        verify(versionJpa, never()).save(any());
    }

    @Test
    void delete_capturesSnapshotBeforeRemoval() {
        KnowledgeArticle persisted = article(11L, "crm.Order", "Order", "body");
        when(jpa.findById(11L)).thenReturn(Optional.of(persisted));
        when(versionJpa.findTopByArticleFqnOrderByVersionNumberDesc("crm.Order"))
                .thenReturn(Optional.empty());

        repo.delete(11L);

        ArgumentCaptor<KnowledgeArticleVersion> captor =
                ArgumentCaptor.forClass(KnowledgeArticleVersion.class);
        verify(versionJpa).save(captor.capture());
        assertThat(captor.getValue().getSnapshotReason()).isEqualTo("delete");
        assertThat(captor.getValue().getArticleFqn()).isEqualTo("crm.Order");
        verify(jpa).deleteById(11L);
    }

    @Test
    void delete_unknownId_isNoOp() {
        when(jpa.findById(99L)).thenReturn(Optional.empty());

        repo.delete(99L);

        verify(versionJpa, never()).save(any());
        verify(jpa).deleteById(99L); // parent still calls (idempotent)
    }

    @Test
    void restoreVersion_loadsTargetAndCopiesFieldsToLive() {
        KnowledgeArticle live = article(7L, "crm.Customer", "Current Title", "current body");
        live.setStatus("DRAFT");
        when(jpa.findByFullyQualifiedName("crm.Customer")).thenReturn(Optional.of(live));

        KnowledgeArticleVersion v1 = versionSnap("crm.Customer", 1);
        v1.setTitle("Original Title");
        v1.setBody("original body");
        v1.setStatus("DRAFT");
        when(versionJpa.findByArticleFqnOrderByVersionNumberDesc("crm.Customer"))
                .thenReturn(java.util.List.of(v1));
        when(versionJpa.findTopByArticleFqnOrderByVersionNumberDesc("crm.Customer"))
                .thenReturn(Optional.of(v1));
        when(jpa.save(any(KnowledgeArticle.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<KnowledgeArticle> restored = repo.restoreVersion("crm.Customer", 1);

        assertThat(restored).isPresent();
        assertThat(restored.get().getTitle()).isEqualTo("Original Title");
        assertThat(restored.get().getBody()).isEqualTo("original body");
        assertThat(restored.get().getStatus()).isEqualTo("DRAFT");

        // The restore itself was snapshotted with reason=restore.
        ArgumentCaptor<KnowledgeArticleVersion> captor =
                ArgumentCaptor.forClass(KnowledgeArticleVersion.class);
        verify(versionJpa, times(1)).save(captor.capture());
        KnowledgeArticleVersion restoreSnap = captor.getValue();
        assertThat(restoreSnap.getSnapshotReason()).isEqualTo("restore");
        assertThat(restoreSnap.getTitle()).isEqualTo("Current Title");
        assertThat(restoreSnap.getBody()).isEqualTo("current body");
    }

    @Test
    void restoreVersion_unknownVersion_returnsEmpty() {
        when(versionJpa.findByArticleFqnOrderByVersionNumberDesc("ghost"))
                .thenReturn(java.util.List.of());

        Optional<KnowledgeArticle> result = repo.restoreVersion("ghost", 99);
        assertThat(result).isEmpty();
        verify(jpa, never()).save(any());
    }

    @Test
    void listVersions_delegatesToJPA() {
        java.util.List<KnowledgeArticleVersion> versions = java.util.List.of(
                versionSnap("crm.Customer", 2),
                versionSnap("crm.Customer", 1));
        when(versionJpa.findByArticleFqnOrderByVersionNumberDesc("crm.Customer"))
                .thenReturn(versions);

        assertThat(repo.listVersions("crm.Customer")).hasSize(2);
    }

    private static KnowledgeArticleVersion versionSnap(String fqn, int n) {
        KnowledgeArticleVersion v = new KnowledgeArticleVersion();
        v.setArticleFqn(fqn);
        v.setVersionNumber(n);
        v.setSnapshotAt(Instant.now());
        v.setSnapshotReason("update");
        v.setTitle("snap-" + n);
        v.setBody("body-" + n);
        return v;
    }
}