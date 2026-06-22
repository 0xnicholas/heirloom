package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.knowledge.sync.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
@Service
public class KnowledgeSyncService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncService.class);
    private final KnowledgeSourceJpaRepository sourceJpa;
    private final KnowledgeArticleJpaRepository articleJpa;
    private final KnowledgeArticleRepository articleRepo;
    public KnowledgeSyncService(KnowledgeSourceJpaRepository sj, KnowledgeArticleJpaRepository aj, KnowledgeArticleRepository ar) { sourceJpa=sj; articleJpa=aj; articleRepo=ar; }
    public SyncReport sync(String sourceFqn) {
        KnowledgeSource s = sourceJpa.findByFullyQualifiedName(sourceFqn).orElseThrow(()->new IllegalArgumentException("Source not found: "+sourceFqn));
        KnowledgeSyncEngine engine = new KnowledgeSyncEngine(articleRepo);
        SyncReport report = engine.sync(s);
        // Post-sync: generate index.md and log.md
        try {
            Path root = Path.of(s.getPath());
            new IndexGenerator().generate(articleJpa, root, sourceFqn);
            if (report.hasChanges() || report.getErrors() > 0) {
                new LogGenerator().appendLog(root, engine.getLastDiff(), report);
            }
        } catch (Exception e) { log.warn("Post-sync generation failed: {}", e.getMessage()); }
        return report;
    }
}
