package com.heirloom.knowledge.repository;
import com.heirloom.knowledge.domain.KnowledgeSource;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface KnowledgeSourceJpaRepository extends JpaRepository<KnowledgeSource, Long> {
    Optional<KnowledgeSource> findByFullyQualifiedName(String fqn);
}
