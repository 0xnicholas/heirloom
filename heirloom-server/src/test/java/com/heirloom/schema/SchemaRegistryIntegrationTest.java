package com.heirloom.schema;

import com.heirloom.HeirloomApplication;
import com.heirloom.schema.domain.*;
import com.heirloom.schema.dto.CreateTypeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = HeirloomApplication.class
)
@Testcontainers
@ActiveProfiles("test")
class SchemaRegistryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        // Clean slate between tests
        rest.delete("/api/types/Customer");
        rest.delete("/api/types/Order");
    }

    @Nested
    @DisplayName("POST /api/types")
    class CreateType {

        @Test
        @DisplayName("creates a valid resource type")
        void createsValidType() {
            var req = new CreateTypeRequest(
                "Customer",
                "A business customer",
                List.of(
                    new Field("name", FieldType.STRING, true),
                    new Field("tier", FieldType.ENUM, false,
                              List.of("free", "pro", "enterprise"))
                ),
                List.of(Ability.KEY, Ability.QUERY, Ability.MUTATE),
                List.of(
                    new StateTransition("Draft", "Active"),
                    new StateTransition("Active", "Frozen")
                ),
                List.of()
            );

            ResponseEntity<Map> response = rest.postForEntity(
                "/api/types", req, Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().get("name")).isEqualTo("Customer");
            assertThat(response.getBody().get("version")).isEqualTo(0);
        }

        @Test
        @DisplayName("rejects duplicate type name")
        void rejectsDuplicateName() {
            var req = new CreateTypeRequest("Customer", null,
                List.of(new Field("name", FieldType.STRING, true)),
                List.of(Ability.KEY),
                List.of(), List.of());

            rest.postForEntity("/api/types", req, Map.class); // first — ok
            ResponseEntity<Map> second = rest.postForEntity(
                "/api/types", req, Map.class);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("rejects relationship with non-existent target type")
        void rejectsBadRelationship() {
            var req = new CreateTypeRequest(
                "Customer",
                null,
                List.of(new Field("name", FieldType.STRING, true)),
                List.of(Ability.KEY),
                List.of(),
                List.of(new Relationship("owns", "GhostType",
                         RelationshipSemantics.OWNERSHIP))
            );

            ResponseEntity<Map> response = rest.postForEntity(
                "/api/types", req, Map.class);

            assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("rejects non-PascalCase type name")
        void rejectsNonPascalCase() {
            var req = new CreateTypeRequest("customer", null,
                List.of(new Field("name", FieldType.STRING, true)),
                List.of(Ability.KEY),
                List.of(), List.of());

            ResponseEntity<Map> response = rest.postForEntity(
                "/api/types", req, Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("GET /api/types")
    class ListTypes {

        @Test
        @DisplayName("returns empty list initially")
        void returnsEmpty() {
            ResponseEntity<List> response = rest.getForEntity(
                "/api/types", List.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEmpty();
        }

        @Test
        @DisplayName("returns created types")
        void returnsCreated() {
            var customer = new CreateTypeRequest("Customer", null,
                List.of(new Field("name", FieldType.STRING, true)),
                List.of(Ability.KEY),
                List.of(), List.of());
            rest.postForEntity("/api/types", customer, Map.class);

            var order = new CreateTypeRequest("Order", null,
                List.of(new Field("total", FieldType.NUMBER, true)),
                List.of(Ability.KEY, Ability.QUERY),
                List.of(), List.of());
            rest.postForEntity("/api/types", order, Map.class);

            ResponseEntity<List> response = rest.getForEntity(
                "/api/types", List.class);

            assertThat(response.getBody()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("GET /api/types/{name}")
    class GetType {

        @Test
        @DisplayName("returns 404 for unknown type")
        void returns404() {
            ResponseEntity<Map> response = rest.getForEntity(
                "/api/types/GhostType", Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
