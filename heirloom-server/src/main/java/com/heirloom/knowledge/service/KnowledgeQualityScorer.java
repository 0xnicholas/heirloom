package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class KnowledgeQualityScorer {

    public record QualityScore(double overall, Map<String, Double> dimensions, String tier) {}

    public QualityScore score(KnowledgeArticle article) {
        double completeness = completenessScore(article);
        double freshness = freshnessScore(article.getLastSyncedAt());
        double richness = clamp(bodyLength(article.getBody()) / 500.0, 0, 1);
        double structure = structureScore(article);
        double connectedness = clamp((double) article.getReferences().size() / 3.0, 0, 1);
        double consistency = article.getFileHash() != null && article.getFileHash().equals(article.getChangeHash()) ? 1.0 : 0.5;

        double total = completeness * 0.25 + freshness * 0.20 + richness * 0.15
                     + structure * 0.15 + connectedness * 0.15 + consistency * 0.10;

        Map<String, Double> dims = new LinkedHashMap<>();
        dims.put("completeness", completeness);
        dims.put("freshness", freshness);
        dims.put("richness", richness);
        dims.put("structure", structure);
        dims.put("connectedness", connectedness);
        dims.put("consistency", consistency);

        String tier = total >= 0.8 ? "excellent" : total >= 0.6 ? "good" : total >= 0.4 ? "fair" : "poor";

        return new QualityScore(Math.round(total * 100.0) / 100.0, dims, tier);
    }

    private double completenessScore(KnowledgeArticle a) {
        if (a.getReferences().isEmpty() && a.getCitations().isEmpty()) return 0.8; // no links isn't bad
        return 0.5 + 0.5 * Math.min(1.0, (double) a.getReferences().size() / 2.0);
    }

    private double freshnessScore(Instant lastSynced) {
        if (lastSynced == null) return 0.3;
        long days = Duration.between(lastSynced, Instant.now()).toDays();
        if (days <= 30) return 1.0;
        if (days <= 90) return 0.7;
        if (days <= 180) return 0.4;
        return 0.1;
    }

    private double structureScore(KnowledgeArticle a) {
        int filled = 0, total = 5;
        if (a.getTitle() != null && !a.getTitle().isBlank()) filled++;
        if (a.getDescription() != null && !a.getDescription().isBlank()) filled++;
        if (a.getTags() != null && !a.getTags().isEmpty()) filled++;
        if (a.getType() != null && !a.getType().isBlank()) filled++;
        if (a.getDomain() != null && !a.getDomain().isBlank()) filled++;
        return (double) filled / total;
    }

    private int bodyLength(String body) {
        return body == null ? 0 : body.length();
    }

    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
