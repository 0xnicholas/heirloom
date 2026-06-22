package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class EmbeddingBatchService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingBatchService.class);
    private static final int BATCH_SIZE = 20;

    private final KnowledgeArticleJpaRepository articleJpa;
    private final EmbeddingProvider provider;
    private final EmbeddingContentBuilder contentBuilder = new EmbeddingContentBuilder();

    public EmbeddingBatchService(KnowledgeArticleJpaRepository articleJpa, EmbeddingProvider provider) {
        this.articleJpa = articleJpa;
        this.provider = provider;
    }

    @Transactional
    public int generateAll(String sourceFqn) {
        if (!provider.isAvailable()) {
            log.debug("Embedding provider not available, skipping");
            return 0;
        }

        List<KnowledgeArticle> pending = articleJpa.findBySourceFqnAndDeletedFalse(sourceFqn)
            .stream()
            .filter(a -> a.getEmbedding() == null)
            .toList();

        if (pending.isEmpty()) return 0;

        int generated = 0;
        for (int i = 0; i < pending.size(); i += BATCH_SIZE) {
            List<KnowledgeArticle> batch = pending.subList(i, Math.min(i + BATCH_SIZE, pending.size()));
            try {
                List<String> texts = batch.stream().map(contentBuilder::build).toList();
                List<float[]> embeddings = provider.embedBatch(texts);
                for (int j = 0; j < batch.size(); j++) {
                    batch.get(j).setEmbedding(embeddings.get(j));
                    articleJpa.save(batch.get(j));
                    generated++;
                }
            } catch (Exception e) {
                log.warn("Batch embedding failed for {} articles: {}", batch.size(), e.getMessage());
            }
        }

        log.info("Generated {} embeddings for source {}", generated, sourceFqn);
        return generated;
    }
}
