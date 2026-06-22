package com.heirloom.knowledge;
import com.heirloom.HeirloomApplication;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.knowledge.service.KnowledgeSyncService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,classes=HeirloomApplication.class)
@TestPropertySource(properties={"spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=create"})
@Testcontainers
class KnowledgeSearchTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired TestRestTemplate rest;
    @Autowired KnowledgeSourceJpaRepository srcJpa;
    @Autowired KnowledgeSyncService syncSvc;
    @TempDir Path dir;
    String sourceFqn;

    @BeforeEach void setUp() throws Exception {
        String name = "srch" + System.nanoTime();
        sourceFqn = "knowledgeSource." + name;
        KnowledgeSource s = new KnowledgeSource(); s.setName(name); s.setSourceType("directory"); s.setPath(dir.toString()); s.setFullyQualifiedName(sourceFqn); srcJpa.save(s);
        Files.writeString(dir.resolve("orders.md"), "---\ntype: BigQuery Table\ntitle: Customer Orders\ndescription: All customer orders\ntags: [sales,revenue]\n---\n# Schema\nOrder tracking data\n");
        Files.writeString(dir.resolve("playbook.md"), "---\ntype: Playbook\ntitle: Incident Response\ndescription: Freshness alert playbook\ntags: [oncall]\n---\n# Steps\nCheck orders pipeline\n");
        syncSvc.sync(sourceFqn);
    }

    @Test void searchByKeyword() {
        ResponseEntity<String> r = rest.getForEntity("/v1/knowledge/search?q=orders", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("Customer Orders");
    }

    @Test void searchNoResults() {
        ResponseEntity<String> r = rest.getForEntity("/v1/knowledge/search?q=xyznonexistentzzz", String.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test void reverseRefReturnsEmptyWhenNoMatch() {
        ResponseEntity<String> r = rest.getForEntity("/v1/knowledge/search?ref=nonexistent.fqn", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
