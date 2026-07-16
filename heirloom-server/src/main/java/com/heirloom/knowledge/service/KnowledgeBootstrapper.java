package com.heirloom.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.discovery.model.RawColumn;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.metadata.domain.TableEntity;
import org.slf4j.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Collections;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<RawColumn>> COLUMN_LIST = new TypeReference<>() {};

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

    /**
     * Render the knowledge draft markdown for a single table.
     *
     * Sections produced:
     *  - YAML frontmatter (type, title, description, source, timestamps, resource link)
     *  - Schema (rendered from {@link TableEntity#getColumnsJson()} — auto block tagged
     *    HEIRLOOM_AUTO_START/HEIRLOOM_AUTO_END so {@code MetadataBlockUpdater} can
     *    refresh just this block on re-discovery)
     *  - Business Context (description, owner, service/schema path, table type)
     *  - Discovery Provenance (FQN, row count, source FQN — auto block for future updates)
     */
    String generateTableMarkdown(TableEntity table) {
        List<RawColumn> columns = parseColumns(table.getColumnsJson());
        StringBuilder sb = new StringBuilder();

        // --- Frontmatter ---
        sb.append("---\n");
        sb.append("type: BigQuery Table\n");
        sb.append("title: ").append(table.getName()).append("\n");
        if (table.getDescription() != null && !table.getDescription().isBlank()) {
            sb.append("description: ").append(table.getDescription()).append("\n");
        }
        if (table.getOwner() != null && !table.getOwner().isBlank()) {
            sb.append("owner: ").append(table.getOwner()).append("\n");
        }
        sb.append("source: heirloom-discovery\n");
        sb.append("discovered_at: ").append(Instant.now()).append("\n");
        sb.append("resource: @").append(table.getFullyQualifiedName()).append("\n");
        sb.append("---\n\n");

        // --- Schema (auto-refreshable block) ---
        sb.append("# Schema\n\n");
        sb.append("<!-- HEIRLOOM_AUTO_START: schema -->\n");
        sb.append("| Column | Type | Nullable | Default | Description |\n");
        sb.append("|--------|------|----------|---------|-------------|\n");
        for (RawColumn col : columns) {
            sb.append("| ").append(escapeCell(col.columnName()))
              .append(" | ").append(escapeCell(col.rawType()))
              .append(" | ").append(col.nullable() ? "YES" : "NO")
              .append(" | ").append(escapeCell(col.defaultValue()))
              .append(" | ").append(escapeCell(col.comment()))
              .append(" |\n");
        }
        sb.append("<!-- HEIRLOOM_AUTO_END: schema -->\n\n");

        // --- Business Context ---
        sb.append("# Business Context\n\n");
        if (table.getDescription() != null && !table.getDescription().isBlank()) {
            sb.append(table.getDescription().trim()).append("\n\n");
        } else {
            sb.append("_No description has been provided for this table yet._\n\n");
            sb.append("> **Action item:** add a short paragraph explaining what this table\n");
            sb.append("> represents, who owns it, and the most common query patterns. This\n");
            sb.append("> context is what agents see first when calling `knowledge.getContext()`.\n\n");
        }
        sb.append("**Owner:** ");
        sb.append(table.getOwner() != null && !table.getOwner().isBlank()
                ? table.getOwner() : "_unassigned_");
        sb.append("\n\n");
        sb.append("**Source:** `").append(
                table.getDatabaseServiceFQN() != null ? table.getDatabaseServiceFQN() : "_unknown_")
          .append("`\n\n");
        if (table.getDatabaseSchemaFQN() != null) {
            sb.append("**Schema:** `").append(table.getDatabaseSchemaFQN()).append("`\n\n");
        }
        sb.append("**Type:** ").append(table.getTableType() != null ? table.getTableType() : "TABLE")
          .append("\n");

        // --- Discovery Provenance (auto block for future updates) ---
        sb.append("\n# Discovery Provenance\n\n");
        sb.append("<!-- HEIRLOOM_AUTO_START: provenance -->\n");
        sb.append("- **FQN:** `").append(table.getFullyQualifiedName()).append("`\n");
        sb.append("- **Discovered at:** ").append(Instant.now()).append("\n");
        sb.append("<!-- HEIRLOOM_AUTO_END: provenance -->\n");

        return sb.toString();
    }

    private static List<RawColumn> parseColumns(String columnsJson) {
        if (columnsJson == null || columnsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(columnsJson, COLUMN_LIST);
        } catch (Exception e) {
            log.warn("Failed to parse columnsJson, rendering empty schema block: {}",
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Escape pipe characters and newlines so markdown table cells stay one row. */
    private static String escapeCell(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}