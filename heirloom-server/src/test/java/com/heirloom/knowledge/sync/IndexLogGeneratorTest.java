package com.heirloom.knowledge.sync;
import com.heirloom.HeirloomApplication;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.knowledge.service.KnowledgeSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,classes=HeirloomApplication.class)
@TestPropertySource(properties={"spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=create"})
@Testcontainers
class IndexLogGeneratorTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired KnowledgeSourceJpaRepository srcJpa;
    @Autowired KnowledgeSyncService syncSvc;
    @TempDir Path dir;

    KnowledgeSource mkSrc(String name) {
        KnowledgeSource s=new KnowledgeSource(); s.setName(name); s.setSourceType("directory"); s.setPath(dir.toString()); s.setFullyQualifiedName("knowledgeSource."+name); return srcJpa.save(s);
    }

    @Test void generatesIndexAfterSync() throws Exception {
        mkSrc("idx"); Files.createDirectories(dir.resolve("tables"));
        Files.writeString(dir.resolve("tables/orders.md"),"---\ntype: BigQuery Table\ntitle: Orders\ndescription: Order records\n---\nB\n");
        Files.writeString(dir.resolve("playbook.md"),"---\ntype: Playbook\ntitle: Incident Guide\n---\nB\n");

        syncSvc.sync("knowledgeSource.idx");

        // Verify root index.md generated
        String rootIdx = Files.readString(dir.resolve("index.md"));
        assertThat(rootIdx).contains("HEIRLOOM_AUTO_START");
        assertThat(rootIdx).contains("[Incident Guide](playbook.md)");
        assertThat(rootIdx).doesNotContain("[Orders]"); // orders is in tables/ subdir

        // Verify tables/index.md
        String tblIdx = Files.readString(dir.resolve("tables/index.md"));
        assertThat(tblIdx).contains("[Orders](orders.md)");
        assertThat(tblIdx).contains("Order records");
    }

    @Test void generatesLogAfterSync() throws Exception {
        mkSrc("log"); Files.writeString(dir.resolve("f.md"),"---\ntype: T\n---\nB\n");
        syncSvc.sync("knowledgeSource.log");
        String log = Files.readString(dir.resolve("log.md"));
        assertThat(log).contains("**Create**");
        assertThat(log).contains("f.md");
    }

    @Test void noLogWhenNoChanges() throws Exception {
        mkSrc("nl"); Files.writeString(dir.resolve("f.md"),"---\ntype: T\n---\nB\n");
        syncSvc.sync("knowledgeSource.nl");
        Files.deleteIfExists(dir.resolve("log.md")); // remove first sync's log
        syncSvc.sync("knowledgeSource.nl"); // second sync, no changes
        assertThat(Files.exists(dir.resolve("log.md"))).isFalse();
    }
}
