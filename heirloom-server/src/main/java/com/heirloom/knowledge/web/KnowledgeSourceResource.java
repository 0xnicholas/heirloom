package com.heirloom.knowledge.web;

import com.heirloom.auth.Authorizer;
import com.heirloom.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.knowledge.service.KnowledgeSyncService;
import com.heirloom.knowledge.service.OkfExportService;
import com.heirloom.knowledge.sync.SyncReport;
import com.heirloom.web.EntityResource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/v1/knowledge/sources")
public class KnowledgeSourceResource extends EntityResource<KnowledgeSource> {

    private final KnowledgeSourceJpaRepository jpa;
    private final KnowledgeSyncService syncService;
    private final OkfExportService exportService;

    public KnowledgeSourceResource(Authorizer a, KnowledgeSourceJpaRepository j,
                                    KnowledgeSyncService s, OkfExportService e) {
        super(EntityRegistry.KNOWLEDGE_SOURCE, a);
        jpa = j; syncService = s; exportService = e;
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeSource>> list() {
        return ResponseEntity.ok(jpa.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeSource> getById(@PathVariable Long id) {
        return jpa.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<KnowledgeSource> create(@RequestBody KnowledgeSource s) {
        s.setFullyQualifiedName("knowledgeSource." + s.getName());
        return ResponseEntity.status(201).body(jpa.save(s));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeSource> update(@PathVariable Long id, @RequestBody KnowledgeSource u) {
        return jpa.findById(id).map(e -> {
            e.setPath(u.getPath());
            e.setSourceType(u.getSourceType());
            e.setDescription(u.getDescription());
            return ResponseEntity.ok(jpa.save(e));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return jpa.findById(id).map(s -> {
            s.setDeleted(true); jpa.save(s);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<SyncReport> sync(@PathVariable Long id) {
        return jpa.findById(id)
            .map(s -> ResponseEntity.ok(syncService.sync(s.getFullyQualifiedName())))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/export")
    public void exportOkf(@PathVariable Long id, HttpServletResponse response) throws IOException {
        KnowledgeSource source = jpa.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Source not found"));
        exportService.exportToZip(source.getFullyQualifiedName(), response);
    }

    /** Webhook endpoint — trigger sync on git push. Accepts GitHub/GitLab payloads. */
    @PostMapping("/webhook/{sourceName}")
    public ResponseEntity<SyncReport> webhookSync(@PathVariable String sourceName) {
        String fqn = "knowledgeSource." + sourceName;
        return jpa.findByFullyQualifiedName(fqn)
            .map(s -> ResponseEntity.ok(syncService.sync(fqn)))
            .orElse(ResponseEntity.notFound().build());
    }
}
