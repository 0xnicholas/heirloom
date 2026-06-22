package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.metadata.domain.TableEntity;
import org.slf4j.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

/**
 * Listens to DiscoveryCompletedEvent and auto-generates markdown knowledge drafts
 * for newly discovered tables in the active KnowledgeSource's _generated/ directory.
 *
 * Phase 0.5c: Direct markdown generation (no Mustache templates yet).
 * Phase 0.5d: Mustache template engine for customizable output.
 */
@Component
public class KnowledgeBootstrapper {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBootstrapper.class);

    private final KnowledgeSourceJpaRepository sourceJpa;

    public KnowledgeBootstrapper(KnowledgeSourceJpaRepository sourceJpa) {
        this.sourceJpa = sourceJpa;
    }

    @EventListener
    public void onDiscoveryCompleted(DiscoveryCompletedEvent event) {
        List<KnowledgeSource> sources = sourceJpa.findAll().stream()
            .filter(s -> "ACTIVE".equals(s.getStatus()))
            .toList();

        if (sources.isEmpty()) {
            log.debug("No active KnowledgeSource — skipping bootstrap");
            return;
        }

        for (KnowledgeSource source : sources) {
            try {
                bootstrapTables(source, event.tables());
            } catch (IOException e) {
                log.warn("Bootstrap failed for source {}: {}", source.getFullyQualifiedName(), e.getMessage());
            }
        }
    }

    private void bootstrapTables(KnowledgeSource source, List<TableEntity> tables) throws IOException {
        Path genDir = Path.of(source.getPath()).resolve("_generated/discovery/tables");
        Files.createDirectories(genDir);

        int generated = 0;
        for (TableEntity table : tables) {
            String safeName = table.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
            Path outFile = genDir.resolve(safeName + ".md");

            if (Files.exists(outFile)) {
                log.debug("Skipping existing draft: {}", outFile);
                continue;
            }

            String md = generateTableMarkdown(table);
            Files.writeString(outFile, md);
            generated++;
        }

        if (generated > 0) {
            log.info("Bootstrapped {} table drafts in {}", generated, genDir);
        }
    }

    String generateTableMarkdown(TableEntity table) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("type: BigQuery Table\n");
        sb.append("title: ").append(table.getName()).append("\n");
        if (table.getDescription() != null && !table.getDescription().isBlank()) {
            sb.append("description: ").append(table.getDescription()).append("\n");
        }
        sb.append("source: heirloom-discovery\n");
        sb.append("discovered_at: ").append(Instant.now()).append("\n");
        sb.append("resource: @").append(table.getFullyQualifiedName()).append("\n");
        sb.append("---\n\n");
        sb.append("# Schema\n\n");
        sb.append("<!-- HEIRLOOM_AUTO_START: schema -->\n");
        sb.append("| Column | Type | Description |\n");
        sb.append("|--------|------|-------------|\n");
        sb.append("<!-- TODO: Column details will be populated in a future update -->\n");
        sb.append("<!-- HEIRLOOM_AUTO_END: schema -->\n\n");
        sb.append("# Usage Notes\n\n");
        sb.append("<!-- TODO: Add business context -->\n");

        return sb.toString();
    }
}
