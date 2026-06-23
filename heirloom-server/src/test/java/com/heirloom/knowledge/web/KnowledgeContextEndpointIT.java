package com.heirloom.knowledge.web;

import com.heirloom.HeirloomApplication;
import com.heirloom.domain.ChangeEvent;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.domain.KnowledgeStatus;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.repository.EventLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = HeirloomApplication.class
)
@Testcontainers
@ActiveProfiles("test")
class KnowledgeContextEndpointIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired TestRestTemplate rest;
    @Autowired KnowledgeArticleJpaRepository jpa;
    @Autowired EventLogRepository eventLog;

    @BeforeEach
    void setup() {
        jpa.deleteAll();
        KnowledgeArticle a = new KnowledgeArticle();
        a.setFullyQualifiedName("crm.Customer");
        a.setTitle("Customer");
        a.setDomain("crm");
        a.setType("Glossary");
        a.setStatus(KnowledgeStatus.PUBLISHED.name());
        a.setBody("# Customer\n\nA customer is a person who buys things.");
        jpa.saveAndFlush(a);
    }

    @Test
    void contextReturnsRootAndEmptyPrereqs() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Agent-Role", "admin");
        ResponseEntity<Map> response = rest.exchange(
            "/v1/knowledge/context?fqn=crm.Customer",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsKeys("root", "prerequisites", "context", "truncated");
        assertThat(((Map) body.get("root")).get("fqn")).isEqualTo("crm.Customer");
    }

    @Test
    void contextEmitsEventInEventLog() {
        long before = countContextFetchEvents();
        HttpHeaders h = new HttpHeaders();
        h.set("X-Agent-Role", "admin");
        rest.exchange("/v1/knowledge/context?fqn=crm.Customer",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(countContextFetchEvents()).isEqualTo(before + 1);
    }

    private long countContextFetchEvents() {
        return eventLog.actorActivity("admin",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(60)).stream()
            .filter(e -> e.getEventType() == ChangeEvent.EventType.KNOWLEDGE_CONTEXT_FETCH)
            .count();
    }
}
