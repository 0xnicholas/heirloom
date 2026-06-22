package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeSource;
import com.heirloom.knowledge.repository.KnowledgeArticleRepository;
import com.heirloom.knowledge.repository.KnowledgeSourceJpaRepository;
import com.heirloom.knowledge.sync.KnowledgeSyncEngine;
import com.heirloom.knowledge.sync.SyncReport;
import org.springframework.stereotype.Service;
@Service
public class KnowledgeSyncService {
    private final KnowledgeSourceJpaRepository sourceJpa;
    private final KnowledgeSyncEngine syncEngine;
    public KnowledgeSyncService(KnowledgeSourceJpaRepository sj, KnowledgeArticleRepository ar) { sourceJpa=sj; syncEngine=new KnowledgeSyncEngine(ar); }
    public SyncReport sync(String sourceFqn) {
        KnowledgeSource s = sourceJpa.findByFullyQualifiedName(sourceFqn).orElseThrow(()->new IllegalArgumentException("Source not found: "+sourceFqn));
        return syncEngine.sync(s);
    }
}
