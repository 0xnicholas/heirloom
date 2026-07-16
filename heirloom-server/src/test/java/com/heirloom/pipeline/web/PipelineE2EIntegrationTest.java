package com.heirloom.pipeline.web;

import com.heirloom.core.pipeline.PipelineStatus;
import com.heirloom.core.pipeline.PipelineTriggerType;
import com.heirloom.pipeline.persistence.*;
import com.heirloom.pipeline.service.PipelineService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PipelineE2EIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired PipelineService pipelineService;
    @Autowired PipelineRunJpaRepository runRepo;
    @Autowired PipelineOutboxJpaRepository outboxRepo;

    @Test
    void triggerRunReturns202WithRunUuid() {
        var resp = rest.postForEntity(
            "http://localhost:" + port + "/v1/pipeline/runs",
            Map.of("sourceFqn", "test.db", "tableFqns", List.of("test.db.t1")),
            Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("runUuid");
        assertThat(resp.getHeaders().getLocation()).isNotNull();
    }

    @Test
    void activeRunConflictReturnsError() {
        rest.postForEntity("http://localhost:" + port + "/v1/pipeline/runs",
            Map.of("sourceFqn", "conflict.db", "tableFqns", List.of("conflict.db.t1")),
            Map.class);

        var resp = rest.postForEntity("http://localhost:" + port + "/v1/pipeline/runs",
            Map.of("sourceFqn", "conflict.db", "tableFqns", List.of("conflict.db.t1")),
            Map.class);

        assertThat(resp.getStatusCode().value()).isIn(409, 500);
    }

    @Test
    void runStatusTransitionsThroughOutboxProcessing() {
        var run = pipelineService.startRun("default", "e2e.db",
            List.of("e2e.db.t1"), PipelineTriggerType.MANUAL);

        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                var status = runRepo.findByRunUuid(run.getRunUuid()).orElseThrow().getStatus();
                assertThat(status).isIn(PipelineStatus.COMPLETED, PipelineStatus.DEAD_LETTER,
                    PipelineStatus.RUNNING, PipelineStatus.RETRYING);
            });

        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                var pending = outboxRepo.findAll().stream()
                    .filter(r -> r.getStatus().equals("PENDING") || r.getStatus().equals("CLAIMED"))
                    .count();
                assertThat(pending).isZero();
            });
    }

    @Test
    void getDeadLetterReturnsList() {
        var resp = rest.getForEntity(
            "http://localhost:" + port + "/v1/pipeline/dead-letter", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}