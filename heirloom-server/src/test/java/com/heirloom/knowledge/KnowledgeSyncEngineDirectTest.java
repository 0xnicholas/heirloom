package com.heirloom.knowledge;
import com.heirloom.HeirloomApplication;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.knowledge.service.KnowledgeSyncService;
import com.heirloom.knowledge.sync.SyncReport;
import org.junit.jupiter.api.*;
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
class KnowledgeSyncEngineDirectTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired KnowledgeSourceJpaRepository srcJpa;
    @Autowired KnowledgeArticleRepository artRepo;
    @Autowired KnowledgeSyncService syncSvc;
    @TempDir Path dir;

    KnowledgeSource mkSrc(String name) {
        KnowledgeSource s=new KnowledgeSource(); s.setName(name); s.setSourceType("directory"); s.setPath(dir.toString()); s.setFullyQualifiedName("knowledgeSource."+name); return srcJpa.save(s);
    }

    @Test void directSync() throws Exception {
        mkSrc("t1"); Files.writeString(dir.resolve("f.md"),"---\ntype: X\ntitle: T\n---\nB\n");
        SyncReport r=syncSvc.sync("knowledgeSource.t1");
        assertThat(r.getCreated()).isEqualTo(1); assertThat(r.getErrors()).isEqualTo(0);
        assertThat(artRepo.getIndexedFileHashes("knowledgeSource.t1")).hasSize(1);
    }

    @Test void noChangeSkip() throws Exception {
        mkSrc("t2"); Files.writeString(dir.resolve("f.md"),"---\ntype: X\n---\nB\n");
        syncSvc.sync("knowledgeSource.t2");
        SyncReport r2=syncSvc.sync("knowledgeSource.t2");
        assertThat(r2.getSkipped()).isEqualTo(1); assertThat(r2.getCreated()).isEqualTo(0);
    }

    @Test void missingTypeError() throws Exception {
        mkSrc("t3"); Files.writeString(dir.resolve("bad.md"),"No frontmatter");
        SyncReport r=syncSvc.sync("knowledgeSource.t3");
        assertThat(r.getErrors()).isEqualTo(1); assertThat(r.getCreated()).isEqualTo(1);
    }
}
