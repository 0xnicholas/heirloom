package com.heirloom.repository;

import com.heirloom.HeirloomApplication;
import com.heirloom.domain.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = HeirloomApplication.class
)
@Testcontainers
@ActiveProfiles("test")
class EventLogDetailsRoundTripIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired EventLogJpaRepository jpa;

    @Test
    void detailsRoundTripsThroughJsonb() {
        ChangeEvent e = new ChangeEvent();
        e.setEventType(ChangeEvent.EventType.KNOWLEDGE_SEARCH);
        e.setActor("test:test-roundtrip-" + System.nanoTime());
        e.setEntityType("knowledge");
        e.setDetails(Map.of(
            "path", "/v1/knowledge/search",
            "resultCount", 7,
            "trimmedCount", 2,
            "_v", 1
        ));
        jpa.save(e);
        jpa.flush();

        Long id = e.getId();
        assertThat(id).isNotNull();
        ChangeEvent loaded = jpa.findById(id).orElseThrow();
        assertThat(loaded.getDetails()).isNotNull();
        assertThat(loaded.getDetails().get("path")).isEqualTo("/v1/knowledge/search");
        assertThat(((Number) loaded.getDetails().get("resultCount")).intValue()).isEqualTo(7);
        assertThat(((Number) loaded.getDetails().get("trimmedCount")).intValue()).isEqualTo(2);
    }
}