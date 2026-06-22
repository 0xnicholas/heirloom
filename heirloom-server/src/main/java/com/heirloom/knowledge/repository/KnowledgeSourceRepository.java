package com.heirloom.knowledge.repository;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.repository.EntityRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
@Repository
public class KnowledgeSourceRepository extends EntityRepository<KnowledgeSource> {
    private final KnowledgeSourceJpaRepository jpa;
    public KnowledgeSourceRepository(KnowledgeSourceJpaRepository jpa) { super(EntityRegistry.KNOWLEDGE_SOURCE, KnowledgeSource.class, jpa); this.jpa = jpa; }
    @PostConstruct void init() { EntityRegistry.register(EntityRegistry.KNOWLEDGE_SOURCE, KnowledgeSource.class, this, null, "knowledgeSource.{name}", "/v1/knowledge/sources"); }
    @Override protected void setFullyQualifiedName(KnowledgeSource s) { s.setFullyQualifiedName("knowledgeSource." + s.getName()); }
    @Override protected void prepareInternal(KnowledgeSource s, boolean isUpdate) {}
}
