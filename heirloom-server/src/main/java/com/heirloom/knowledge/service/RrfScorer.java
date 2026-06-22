package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import java.util.*;

public class RrfScorer {
    private static final double K = 60.0;

    public record ScoredArticle(KnowledgeArticle article, double score) {}

    public List<ScoredArticle> fuse(List<KnowledgeArticle> ftsResults, List<KnowledgeArticle> vectorResults) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, KnowledgeArticle> articles = new LinkedHashMap<>();

        for (int i = 0; i < ftsResults.size(); i++) {
            KnowledgeArticle a = ftsResults.get(i);
            scores.merge(a.getFullyQualifiedName(), 1.0 / (K + i + 1), Double::sum);
            articles.putIfAbsent(a.getFullyQualifiedName(), a);
        }
        for (int i = 0; i < vectorResults.size(); i++) {
            KnowledgeArticle a = vectorResults.get(i);
            scores.merge(a.getFullyQualifiedName(), 1.0 / (K + i + 1), Double::sum);
            articles.putIfAbsent(a.getFullyQualifiedName(), a);
        }

        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(e -> new ScoredArticle(articles.get(e.getKey()), Math.round(e.getValue() * 10000.0) / 10000.0))
            .toList();
    }
}
