package com.heirloom.knowledge.service;

import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.metadata.domain.TableEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KnowledgeBootstrapperTest {

    private final KnowledgeBootstrapper bootstrapper =
            new KnowledgeBootstrapper(mock(KnowledgeSourceJpaRepository.class));

    @Test
    void rendersColumnsFromColumnsJson() {
        TableEntity table = new TableEntity();
        table.setName("customers");
        table.setFullyQualifiedName("prod.public.customers");
        table.setDescription("Master customer records");
        table.setOwner("data-team@acme.com");
        table.setDatabaseServiceFQN("prod.postgres");
        table.setDatabaseSchemaFQN("prod.public");
        table.setColumnsJson(
            "[{\"columnName\":\"id\",\"rawType\":\"BIGINT\",\"nullable\":false,\"comment\":\"PK\",\"defaultValue\":null}," +
            "{\"columnName\":\"email\",\"rawType\":\"VARCHAR(255)\",\"nullable\":true,\"comment\":\"login email\",\"defaultValue\":null}," +
            "{\"columnName\":\"status\",\"rawType\":\"TEXT\",\"nullable\":false,\"comment\":\"active|disabled\",\"defaultValue\":\"'active'\"}]"
        );

        String md = bootstrapper.generateTableMarkdown(table);

        // Frontmatter
        assertThat(md).startsWith("---\n");
        assertThat(md).contains("title: customers");
        assertThat(md).contains("description: Master customer records");
        assertThat(md).contains("owner: data-team@acme.com");
        assertThat(md).contains("resource: @prod.public.customers");

        // Schema block
        assertThat(md).contains("<!-- HEIRLOOM_AUTO_START: schema -->");
        assertThat(md).contains("<!-- HEIRLOOM_AUTO_END: schema -->");
        assertThat(md).contains("| id | BIGINT | NO |  | PK |");
        assertThat(md).contains("| email | VARCHAR(255) | YES |  | login email |");
        assertThat(md).contains("| status | TEXT | NO | 'active' | active\\|disabled |");

        // Business Context
        assertThat(md).contains("# Business Context");
        assertThat(md).contains("Master customer records");
        assertThat(md).contains("**Owner:** data-team@acme.com");
        assertThat(md).contains("**Source:** `prod.postgres`");
        assertThat(md).contains("**Schema:** `prod.public`");

        // Provenance
        assertThat(md).contains("<!-- HEIRLOOM_AUTO_START: provenance -->");
        assertThat(md).contains("- **FQN:** `prod.public.customers`");
    }

    @Test
    void emptyColumnsRendersEmptySchemaTable() {
        TableEntity table = new TableEntity();
        table.setName("empty_table");
        table.setFullyQualifiedName("prod.public.empty_table");
        table.setColumnsJson("[]");

        String md = bootstrapper.generateTableMarkdown(table);

        // Header still rendered, no column rows
        assertThat(md).contains("| Column | Type | Nullable | Default | Description |");
        assertThat(md).contains("|--------|------|----------|---------|-------------|");
        // No leftover TODO placeholder
        assertThat(md).doesNotContain("TODO");
    }

    @Test
    void malformedColumnsJsonFallsBackGracefully() {
        TableEntity table = new TableEntity();
        table.setName("weird");
        table.setFullyQualifiedName("prod.public.weird");
        table.setColumnsJson("not-valid-json{");

        String md = bootstrapper.generateTableMarkdown(table);

        assertThat(md).contains("# Schema");
        assertThat(md).contains("# Business Context");
        assertThat(md).doesNotContain("TODO");
    }

    @Test
    void missingDescriptionRendersActionItemHint() {
        TableEntity table = new TableEntity();
        table.setName("orphans");
        table.setFullyQualifiedName("prod.public.orphans");
        table.setColumnsJson("[]");

        String md = bootstrapper.generateTableMarkdown(table);

        assertThat(md).contains("_No description has been provided for this table yet._");
        assertThat(md).contains("**Action item:** add a short paragraph");
        assertThat(md).contains("**Owner:** _unassigned_");
    }

    @Test
    void cellEscapesPipesAndNewlines() {
        TableEntity table = new TableEntity();
        table.setName("edge");
        table.setFullyQualifiedName("prod.public.edge");
        table.setColumnsJson(
            "[{\"columnName\":\"notes\",\"rawType\":\"TEXT\",\"nullable\":true," +
            "\"comment\":\"contains | pipes\\nand newlines\",\"defaultValue\":null}]");

        String md = bootstrapper.generateTableMarkdown(table);

        // Pipe in comment should be escaped; newlines collapsed to spaces
        assertThat(md).contains("contains \\| pipes and newlines");
        // And the row should still be a single markdown row
        long rowsContainingNotes = md.lines()
                .filter(l -> l.startsWith("| notes |"))
                .count();
        assertThat(rowsContainingNotes).isEqualTo(1);
    }
}