package com.heirloom.knowledge.repository;

import com.heirloom.HeirloomApplication;
import com.heirloom.knowledge.domain.KnowledgeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = HeirloomApplication.class
)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create"
})
@Testcontainers
@DisplayName("KnowledgeSourceJpaRepository")
class KnowledgeSourceJpaRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private KnowledgeSourceJpaRepository repo;

    @Test
    @DisplayName("persists and retrieves by fullyQualifiedName")
    void persistsAndFindsByFQN() {
        var source = new KnowledgeSource();
        source.setName("primary");
        source.setSourceType("directory");
        source.setPath("/data/knowledge/");
        source.setFullyQualifiedName("knowledgeSource.primary");

        repo.save(source);

        Optional<KnowledgeSource> found = repo.findByFullyQualifiedName("knowledgeSource.primary");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("primary");
    }

    @Test
    @DisplayName("returns empty for non-existent FQN")
    void returnsEmptyForUnknownFQN() {
        Optional<KnowledgeSource> found = repo.findByFullyQualifiedName("nonexistent");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("defaults to manual schedule and ACTIVE status")
    void defaultsCorrectly() {
        var source = new KnowledgeSource();
        source.setName("test-src");
        source.setSourceType("git-repo");
        source.setPath("/data/repo/");
        source.setFullyQualifiedName("knowledgeSource.test-src");

        KnowledgeSource saved = repo.save(source);

        assertThat(saved.getSchedule()).isEqualTo("manual");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getBranch()).isEqualTo("main");
        assertThat(saved.getConfig()).isEqualTo("{}");
    }
}
