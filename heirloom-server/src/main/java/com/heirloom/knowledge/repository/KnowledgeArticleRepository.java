package com.heirloom.knowledge.repository;
import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeArticleVersion;
import com.heirloom.repository.EntityRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class KnowledgeArticleRepository extends EntityRepository<KnowledgeArticle> {
    private final KnowledgeArticleJpaRepository jpa;
    private final KnowledgeArticleVersionJpaRepository versionJpa;

    public KnowledgeArticleRepository(KnowledgeArticleJpaRepository jpa,
                                     KnowledgeArticleVersionJpaRepository versionJpa) {
        super(EntityRegistry.KNOWLEDGE_ARTICLE, KnowledgeArticle.class, jpa);
        this.jpa = jpa;
        this.versionJpa = versionJpa;
    }

    @PostConstruct void init() {
        EntityRegistry.register(EntityRegistry.KNOWLEDGE_ARTICLE, KnowledgeArticle.class,
                this, null, "knowledge.{source}.{path}", "/v1/knowledge");
    }

    @Override protected void setFullyQualifiedName(KnowledgeArticle a) {
        String src = a.getSourceFqn();
        if (src != null && src.contains(".")) src = src.substring(src.indexOf('.') + 1);
        else src = "default";
        String p = a.getFilePath().replaceAll("\\.md$", "").replace('/', '.');
        a.setFullyQualifiedName("knowledge." + src + "." + p);
    }

    @Override protected void prepareInternal(KnowledgeArticle a, boolean isUpdate) {}

    @Transactional public KnowledgeArticle syncUpsert(KnowledgeArticle a) {
        Optional<KnowledgeArticle> ex = jpa.findBySourceFqnAndFilePath(a.getSourceFqn(), a.getFilePath());
        if (ex.isPresent()) {
            KnowledgeArticle t = ex.get();
            // Capture version snapshot before mutation.
            captureSnapshot(t, KnowledgeArticleVersion.REASON_UPDATE);
            copyFields(a, t);
            t.setVersion(t.getVersion() + 1);
            return jpa.save(t);
        } else {
            setFullyQualifiedName(a);
            a.setCreatedAt(Instant.now());
            a.setUpdatedAt(Instant.now());
            return jpa.save(a);
        }
    }

    private void copyFields(KnowledgeArticle s, KnowledgeArticle d) {
        d.setFileHash(s.getFileHash()); d.setType(s.getType()); d.setTitle(s.getTitle());
        d.setDescription(s.getDescription());
        d.setBody(s.getBody()); d.setFrontmatterRaw(s.getFrontmatterRaw());
        d.setFrontmatter(s.getFrontmatter());
        d.setTags(s.getTags()); d.setReferences(s.getReferences()); d.setCitations(s.getCitations());
        d.setDomain(s.getDomain()); d.setAuthor(s.getAuthor()); d.setResource(s.getResource());
        d.setSyncStatus(s.getSyncStatus()); d.setSyncError(s.getSyncError());
        d.setLastSyncedAt(Instant.now());
        d.setUpdatedAt(Instant.now()); d.setStatus(s.getStatus());
    }

    // === Phase 4.1: version snapshot hooks ===

    @Override
    public KnowledgeArticle update(KnowledgeArticle entity) {
        // Snapshot the persisted state before mutating.
        Optional<KnowledgeArticle> existing =
                entity.getId() != null ? jpa.findById(entity.getId()) : Optional.empty();
        existing.ifPresent(a -> captureSnapshot(a, KnowledgeArticleVersion.REASON_UPDATE));
        return super.doUpdate(entity);
    }

    @Override
    public void delete(Long id) {
        jpa.findById(id).ifPresent(a -> captureSnapshot(a, KnowledgeArticleVersion.REASON_DELETE));
        super.delete(id);
    }

    /**
     * Capture a snapshot of the article's current persisted state. Called
     * before any mutation; the snapshot stores the OLD state so the live row
     * after the mutation represents the latest version.
     */
    private void captureSnapshot(KnowledgeArticle source, String reason) {
        KnowledgeArticleVersion v = new KnowledgeArticleVersion();
        v.setArticleId(source.getId());
        v.setArticleFqn(source.getFullyQualifiedName());
        v.setVersionNumber(nextVersionNumber(source.getFullyQualifiedName()));
        v.setSnapshotAt(Instant.now());
        v.setSnapshotReason(reason);
        v.setTitle(source.getTitle());
        v.setDescription(source.getDescription());
        v.setBody(source.getBody());
        v.setStatus(source.getStatus());
        v.setType(source.getType());
        v.setDomain(source.getDomain());
        v.setAuthor(source.getAuthor());
        v.setOwner(source.getOwner());
        v.setResource(source.getResource());
        v.setFilePath(source.getFilePath());
        v.setFileHash(source.getFileHash());
        v.setVersion(source.getVersion());
        versionJpa.save(v);
    }

    /** Next version number for an article FQN = max(existing) + 1, or 1. */
    private int nextVersionNumber(String fqn) {
        return versionJpa.findTopByArticleFqnOrderByVersionNumberDesc(fqn)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);
    }

    // === Version query helpers ===

    public List<KnowledgeArticleVersion> listVersions(String fqn) {
        return versionJpa.findByArticleFqnOrderByVersionNumberDesc(fqn);
    }

    public Optional<KnowledgeArticleVersion> latestVersion(String fqn) {
        return versionJpa.findTopByArticleFqnOrderByVersionNumberDesc(fqn);
    }

    public Optional<KnowledgeArticleVersion> findVersion(Long id) {
        return versionJpa.findById(id);
    }

    public Optional<KnowledgeArticleVersion> findVersion(String fqn, int versionNumber) {
        return versionJpa.findByArticleFqnOrderByVersionNumberDesc(fqn).stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst();
    }

    /**
     * Restore an article to a prior version's snapshot state. Captures the
     * current state as a snapshot (reason=restore) before applying, so the
     * restore itself is part of the history.
     *
     * @return the restored article, or empty if the target version wasn't found.
     */
    @Transactional
    public Optional<KnowledgeArticle> restoreVersion(String fqn, int versionNumber) {
        Optional<KnowledgeArticleVersion> target = findVersion(fqn, versionNumber);
        if (target.isEmpty()) return Optional.empty();

        Optional<KnowledgeArticle> liveOpt = jpa.findByFullyQualifiedName(fqn);
        if (liveOpt.isEmpty()) return Optional.empty();

        KnowledgeArticle live = liveOpt.get();
        captureSnapshot(live, KnowledgeArticleVersion.REASON_RESTORE);

        KnowledgeArticleVersion v = target.get();
        live.setTitle(v.getTitle());
        live.setDescription(v.getDescription());
        live.setBody(v.getBody());
        live.setStatus(v.getStatus());
        live.setType(v.getType());
        live.setDomain(v.getDomain());
        live.setAuthor(v.getAuthor());
        live.setOwner(v.getOwner());
        live.setResource(v.getResource());
        live.setFilePath(v.getFilePath());
        live.setFileHash(v.getFileHash());
        live.setVersion(live.getVersion() + 1);
        live.setUpdatedAt(Instant.now());
        return Optional.of(jpa.save(live));
    }

    public Optional<KnowledgeArticle> findByFilePath(String sf, String fp) {
        return jpa.findBySourceFqnAndFilePath(sf, fp);
    }

    public Map<String, String> getIndexedFileHashes(String sf) {
        return jpa.findBySourceFqnAndDeletedFalse(sf).stream()
                .collect(Collectors.toMap(KnowledgeArticle::getFilePath, KnowledgeArticle::getFileHash));
    }
}