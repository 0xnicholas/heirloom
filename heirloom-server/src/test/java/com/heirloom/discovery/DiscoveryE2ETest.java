package com.heirloom.discovery;

import com.heirloom.core.alignment.AlignmentService;
import com.heirloom.core.discovery.DiscoveryConfig;
import com.heirloom.connector.postgres.PostgresSchemaExtractor;
import com.heirloom.discovery.inference.InferenceContext;
import com.heirloom.discovery.inference.InferencePipeline;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.discovery.runner.DiscoveryRunner;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class DiscoveryE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("heirloom_test")
        .withUsername("test")
        .withPassword("test");

    private static PostgresSchemaExtractor extractor;
    private static DiscoveryConfig config;

    @BeforeAll
    static void setUp() throws Exception {
        // Create test schema
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE customers (
                    id SERIAL PRIMARY KEY,
                    name TEXT NOT NULL,
                    email TEXT,
                    tier TEXT DEFAULT 'free',
                    created_at TIMESTAMP DEFAULT NOW()
                )
                """);

            stmt.execute("""
                CREATE TABLE orders (
                    id SERIAL PRIMARY KEY,
                    customer_id INTEGER REFERENCES customers(id),
                    total NUMERIC(10,2) NOT NULL,
                    status TEXT DEFAULT 'draft',
                    created_at TIMESTAMP DEFAULT NOW()
                )
                """);

            stmt.execute("COMMENT ON TABLE customers IS 'Customer accounts'");
            stmt.execute("COMMENT ON COLUMN customers.email IS 'Primary contact email'");
        }

        config = new DiscoveryConfig("postgresql", "localhost",
            postgres.getMappedPort(5432), postgres.getDatabaseName(),
            postgres.getUsername(), postgres.getPassword(), "public");

        extractor = new PostgresSchemaExtractor();
        extractor.prepare(config);
    }

    @Test
    void shouldExtractSchema() {
        assertThat(extractor.testConnection()).isTrue();

        RawSchema schema = extractor.extract(config);

        assertThat(schema.tables()).hasSize(2);
        assertThat(schema.sourceType()).isEqualTo("postgresql");
    }

    @Test
    void shouldExtractTableMetadata() {
        RawSchema schema = extractor.extract(config);

        var customers = schema.tables().stream()
            .filter(t -> t.tableName().equals("customers"))
            .findFirst().orElseThrow();

        assertThat(customers.columns()).hasSize(5); // id, name, email, tier, created_at
        assertThat(customers.comment()).isEqualTo("Customer accounts");

        var email = customers.columns().stream()
            .filter(c -> c.columnName().equals("email"))
            .findFirst().orElseThrow();
        assertThat(email.nullable()).isTrue();
    }

    @Test
    void shouldExtractConstraints() {
        RawSchema schema = extractor.extract(config);

        var orders = schema.tables().stream()
            .filter(t -> t.tableName().equals("orders"))
            .findFirst().orElseThrow();

        var fks = orders.constraints().stream()
            .filter(c -> c.type() == com.heirloom.core.discovery.model.RawConstraint.ConstraintType.FOREIGN_KEY)
            .toList();

        assertThat(fks).isNotEmpty();
        assertThat(fks.get(0).targetTable()).isEqualTo("customers");
    }

    @Test
    void shouldInferResourceTypes() {
        RawSchema schema = extractor.extract(config);
        InferencePipeline pipeline = new InferencePipeline((AlignmentService) null);
        InferenceContext ctx = new InferenceContext(schema, null, null, List.of(), "test");
        List<ResourceTypeProposal> proposals = pipeline.infer(ctx);

        assertThat(proposals).hasSize(2);

        var customer = proposals.stream()
            .filter(p -> p.proposedTypeName().equals("Customers"))
            .findFirst().orElseThrow();

        assertThat(customer.isHighConfidence()).isTrue();
        assertThat(customer.fields()).isNotEmpty();

        // Verify FieldMapper mapped PG types correctly
        var nameField = customer.fields().stream()
            .filter(f -> f.name().equals("name")).findFirst().orElseThrow();
        assertThat(nameField.type()).isEqualTo(com.heirloom.schema.domain.FieldType.STRING);
        assertThat(nameField.required()).isTrue(); // NOT NULL

        var totalField = proposals.stream()
            .filter(p -> p.proposedTypeName().equals("Orders"))
            .flatMap(p -> p.fields().stream())
            .filter(f -> f.name().equals("total"))
            .findFirst().orElseThrow();
        assertThat(totalField.type()).isEqualTo(com.heirloom.schema.domain.FieldType.NUMBER);
    }

    @Test
    void shouldInferRelationships() {
        RawSchema schema = extractor.extract(config);
        InferencePipeline pipeline = new InferencePipeline((AlignmentService) null);
        InferenceContext ctx = new InferenceContext(schema, null, null, List.of(), "test");
        List<ResourceTypeProposal> proposals = pipeline.infer(ctx);

        var orders = proposals.stream()
            .filter(p -> p.proposedTypeName().equals("Orders"))
            .findFirst().orElseThrow();

        assertThat(orders.relationships()).isNotEmpty();
        assertThat(orders.relationships().get(0).label()).isEqualTo("customer");
        assertThat(orders.relationships().get(0).targetType()).isEqualTo("Customers");
        assertThat(orders.relationships().get(0).semantics()).isEqualTo(
            com.heirloom.schema.domain.RelationshipSemantics.REFERENCE);
    }

    @Test
    void shouldGenerateContentHash() {
        RawSchema schema1 = extractor.extract(config);
        RawSchema schema2 = extractor.extract(config);

        // Same schema → same hash
        assertThat(schema1.contentHash()).isEqualTo(schema2.contentHash());
    }
}
