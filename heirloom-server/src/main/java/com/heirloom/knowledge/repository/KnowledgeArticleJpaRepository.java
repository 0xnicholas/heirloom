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
}
