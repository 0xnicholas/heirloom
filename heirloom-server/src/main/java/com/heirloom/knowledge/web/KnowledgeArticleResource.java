package com.heirloom.knowledge.web;
import com.heirloom.auth.Authorizer;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.web.EntityResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/v1/knowledge")
public class KnowledgeArticleResource extends EntityResource<KnowledgeArticle> {
    private final KnowledgeArticleJpaRepository jpa;
    public KnowledgeArticleResource(Authorizer a, KnowledgeArticleJpaRepository j) { super(EntityRegistry.KNOWLEDGE_ARTICLE, a); jpa=j; }
    @GetMapping public ResponseEntity<List<KnowledgeArticle>> list() { return ResponseEntity.ok(jpa.findAll()); }
    @GetMapping("/{id}") public ResponseEntity<KnowledgeArticle> getById(@PathVariable Long id) { return jpa.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()); }
    @GetMapping("/name/{fqn}") public ResponseEntity<KnowledgeArticle> getByFQN(@PathVariable String fqn) { return jpa.findByFullyQualifiedName(fqn).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()); }
}
