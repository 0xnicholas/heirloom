package com.heirloom.knowledge.web;

import com.heirloom.auth.Authorizer;
import com.heirloom.core.entity.EntityRegistry;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.knowledge.service.KnowledgeSyncService;
import com.heirloom.knowledge.service.OkfExportService;
import com.heirloom.knowledge.service.HtmlImporter;
import com.heirloom.knowledge.service.KnowledgeImporter;
import com.heirloom.knowledge.sync.SyncReport;
import com.heirloom.web.EntityResource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/knowledge/sources")
public class KnowledgeSourceResource extends EntityResource<KnowledgeSource> {

    private final KnowledgeSourceJpaRepository jpa;
    private final KnowledgeSyncService syncService;
    private final OkfExportService exportService;
    private final HtmlImporter htmlImporter;

    public KnowledgeSourceResource(Authorizer a, KnowledgeSourceJpaRepository j,
                                    KnowledgeSyncService s, OkfExportService e,
                                    HtmlImporter h) {
        super(EntityRegistry.KNOWLEDGE_SOURCE, a);
        jpa = j; syncService = s; exportService = e; htmlImporter = h;
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

    /** Import external HTML content as a knowledge article. */
    @PostMapping("/{id}/import")
    public ResponseEntity<?> importHtml(@PathVariable Long id, @RequestBody Map<String,String> body) {
        return jpa.findById(id).map(source -> {
            try {
                String title = body.getOrDefault("title", "Imported Document");
                String html = body.get("html");
                if (html == null || html.isBlank()) return ResponseEntity.badRequest().body(Map.of("error","html field required"));
                String md = htmlImporter.convertToMarkdown(
                    new KnowledgeImporter.ImportEntry("1", title, html, "html", null, List.of(), null));
                String filename = title.replaceAll("[^a-zA-Z0-9_-]", "_") + ".md";
                java.nio.file.Path outPath = java.nio.file.Path.of(source.getPath()).resolve("imports").resolve(filename);
                java.nio.file.Files.createDirectories(outPath.getParent());
                String fullMd = "---\ntype: Imported Document\ntitle: " + title + "\nsource: heirloom-import\n---\n" + md;
                java.nio.file.Files.writeString(outPath, fullMd);
                SyncReport report = syncService.sync(source.getFullyQualifiedName());
                return ResponseEntity.ok(Map.of("status","imported","file",filename,"sync",report));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error",e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }
}
