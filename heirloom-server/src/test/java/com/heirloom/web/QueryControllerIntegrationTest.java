package com.heirloom.web;

import com.heirloom.HeirloomApplication;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.repository.TableRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
class QueryControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired TestRestTemplate rest;
    @Autowired TableRepository tableRepo;

    @Test
    void shouldExecuteSemanticQuery() {
        Map<String, Object> body = Map.of(
            "mode", "semantic",
            "payload", Map.of("type", "Customer", "filter", Map.of("tier", "Gold"))
        );
        ResponseEntity<Map> resp = postJson("/v1/query", body, Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void shouldExecuteRawQuery() {
        String fqn = registerTable("test_raw");
        try {
            Map<String, Object> body = Map.of(
                "mode", "raw",
                "rawTable", fqn,
                "rawSql", "SELECT * FROM {table} LIMIT 5"
            );
            ResponseEntity<Map> resp = postJson("/v1/query", body, Map.class);
            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        } finally {
            deleteByFQN(fqn);
        }
    }

    @Test
    void shouldExecuteHybridQuery() {
        String fqn = registerTable("test_hybrid");
        try {
            Map<String, Object> body = Map.of(
                "mode", "hybrid",
                "resource", Map.of("type", "Customer", "rid", "default.Customer.1", "fields", List.of("customer_id")),
                "drillDown", Map.of("rawTable", fqn, "rawSql", "SELECT * FROM {table} WHERE id = 1 LIMIT 5")
            );
            ResponseEntity<Map> resp = postJson("/v1/query", body, Map.class);
            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        } finally {
            deleteByFQN(fqn);
        }
    }

    @Test
    void shouldRejectRawQueryWithoutLimit() {
        String fqn = registerTable("test_nolimit");
        try {
            Map<String, Object> body = Map.of(
                "mode", "raw",
                "rawTable", fqn,
                "rawSql", "SELECT * FROM {table}"
            );
            ResponseEntity<Map> resp = postJson("/v1/query", body, Map.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(500);
        } finally {
            deleteByFQN(fqn);
        }
    }

    private String registerTable(String name) {
        TableEntity table = new TableEntity();
        table.setName(name);
        table.setDatabaseServiceFQN("svc");
        table.setDatabaseFQN("db");
        table.setColumnsJson("[{\"name\":\"id\",\"dataType\":\"integer\"}]");
        tableRepo.create(table);
        return table.getFullyQualifiedName();
    }

    private void deleteByFQN(String fqn) {
        tableRepo.findByFQN(fqn).ifPresent(t -> tableRepo.delete(t.getId()));
    }

    private <T> ResponseEntity<T> postJson(String path, Object body, Class<T> responseType) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), responseType);
    }
}
