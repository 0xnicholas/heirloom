package com.heirloom.knowledge.repository;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeArticle;
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
    public KnowledgeArticleRepository(KnowledgeArticleJpaRepository jpa) { super(EntityRegistry.KNOWLEDGE_ARTICLE, KnowledgeArticle.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register(EntityRegistry.KNOWLEDGE_ARTICLE, KnowledgeArticle.class, this, null, "knowledge.{source}.{path}", "/v1/knowledge"); }
    @Override protected void setFullyQualifiedName(KnowledgeArticle a) {
        String src = a.getSourceFqn(); if (src != null && src.contains(".")) src = src.substring(src.indexOf('.') + 1); else src = "default";
        String p = a.getFilePath().replaceAll("\\.md$", "").replace('/', '.');
        a.setFullyQualifiedName("knowledge." + src + "." + p);
    }
    @Override protected void prepareInternal(KnowledgeArticle a, boolean isUpdate) {}
    @Transactional public KnowledgeArticle syncUpsert(KnowledgeArticle a) {
        Optional<KnowledgeArticle> ex = jpa.findBySourceFqnAndFilePath(a.getSourceFqn(), a.getFilePath());
        if (ex.isPresent()) {
            KnowledgeArticle t = ex.get(); copyFields(a, t); t.setVersion(t.getVersion() + 1); return jpa.save(t);
        } else { setFullyQualifiedName(a); a.setCreatedAt(Instant.now()); a.setUpdatedAt(Instant.now()); return jpa.save(a); }
    }
    private void copyFields(KnowledgeArticle s, KnowledgeArticle d) {
        d.setFileHash(s.getFileHash()); d.setType(s.getType()); d.setTitle(s.getTitle()); d.setDescription(s.getDescription());
        d.setBody(s.getBody()); d.setFrontmatterRaw(s.getFrontmatterRaw()); d.setFrontmatter(s.getFrontmatter());
        d.setTags(s.getTags()); d.setReferences(s.getReferences()); d.setCitations(s.getCitations());
        d.setDomain(s.getDomain()); d.setAuthor(s.getAuthor()); d.setResource(s.getResource());
        d.setSyncStatus(s.getSyncStatus()); d.setSyncError(s.getSyncError()); d.setLastSyncedAt(Instant.now());
        d.setUpdatedAt(Instant.now()); d.setStatus(s.getStatus());
    }
    public Optional<KnowledgeArticle> findByFilePath(String sf, String fp) { return jpa.findBySourceFqnAndFilePath(sf, fp); }
    public Map<String,String> getIndexedFileHashes(String sf) { return jpa.findBySourceFqnAndDeletedFalse(sf).stream().collect(Collectors.toMap(KnowledgeArticle::getFilePath, KnowledgeArticle::getFileHash)); }
}
