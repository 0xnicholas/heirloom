package com.heirloom.knowledge.repository;

import com.heirloom.knowledge.domain.KnowledgeArticleVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeArticleVersionJpaRepository extends JpaRepository<KnowledgeArticleVersion, Long> {
    List<KnowledgeArticleVersion> findByArticleFqnOrderByVersionNumberDesc(String articleFqn);
    Optional<KnowledgeArticleVersion> findTopByArticleFqnOrderByVersionNumberDesc(String articleFqn);
}