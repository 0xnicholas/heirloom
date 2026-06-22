package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class KnowledgePromotionEngine {
    private static final Logger log = LoggerFactory.getLogger(KnowledgePromotionEngine.class);
    private final KnowledgeArticleJpaRepository articleJpa;
    private final GlossaryExtractor glossaryExtractor = new GlossaryExtractor();

    public KnowledgePromotionEngine(KnowledgeArticleJpaRepository articleJpa) {
        this.articleJpa = articleJpa;
    }

    public record PromotionReport(List<GlossaryExtractor.ExtractedTerm> terms,
                                   List<String> skippedDuplicates, int totalCandidates) {}

    /** Scan all published knowledge articles for glossary terms. */
    public PromotionReport promote(String sourceFqn) {
        List<KnowledgeArticle> articles = articleJpa.findBySourceFqnAndDeletedFalse(sourceFqn);
        List<GlossaryExtractor.ExtractedTerm> allTerms = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> skipped = new ArrayList<>();

        for (KnowledgeArticle article : articles) {
            if (article.getBody() == null) continue;
            var terms = glossaryExtractor.extract(article.getBody(), article.getFullyQualifiedName());
            for (var t : terms) {
                String key = t.term().toLowerCase();
                if (t.englishName() != null) key += "|" + t.englishName().toLowerCase();
                if (seen.contains(key)) { skipped.add(t.term()); continue; }
                seen.add(key);
                allTerms.add(t);
            }
        }

        log.info("Promotion: {} terms extracted, {} duplicates skipped from {} articles",
            allTerms.size(), skipped.size(), articles.size());
        return new PromotionReport(allTerms, skipped, allTerms.size());
    }
}
