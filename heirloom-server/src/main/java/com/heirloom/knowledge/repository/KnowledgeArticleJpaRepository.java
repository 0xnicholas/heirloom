package com.heirloom.knowledge.repository;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
public interface KnowledgeArticleJpaRepository extends JpaRepository<KnowledgeArticle, Long> {
    Optional<KnowledgeArticle> findByFullyQualifiedName(String fqn);
    Optional<KnowledgeArticle> findBySourceFqnAndFilePath(String sourceFqn, String filePath);
    @Query("SELECT a FROM KnowledgeArticle a WHERE a.sourceFqn = :sourceFqn AND a.deleted = false")
    List<KnowledgeArticle> findBySourceFqnAndDeletedFalse(@Param("sourceFqn") String sourceFqn);

    @Query(value = "SELECT * FROM knowledge_articles WHERE to_tsvector('english', coalesce(title,'') || ' ' || coalesce(description,'') || ' ' || coalesce(body,'')) @@ to_tsquery('english', :query) AND deleted = false AND status = 'published' ORDER BY ts_rank(to_tsvector('english', coalesce(title,'') || ' ' || coalesce(description,'') || ' ' || coalesce(body,'')), to_tsquery('english', :query)) DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<KnowledgeArticle> search(@Param("query") String query, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = "SELECT a.* FROM knowledge_articles a WHERE a.references_jsonb @> CAST(:refFilter AS jsonb) AND a.deleted = false ORDER BY a.updated_at DESC", nativeQuery = true)
    List<KnowledgeArticle> findByEntityRef(@Param("refFilter") String refFilter);
}
