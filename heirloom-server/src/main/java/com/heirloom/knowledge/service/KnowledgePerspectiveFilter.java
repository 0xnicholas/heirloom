package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class KnowledgePerspectiveFilter {

    public record AccessPolicy(List<String> allowedDomains, List<String> deniedTypes, boolean isAdmin) {}

    /** Default policy — no restrictions (Phase 0-1: no auth) */
    public AccessPolicy resolvePolicy(String actorId) {
        return new AccessPolicy(List.of("*"), List.of(), false);
    }

    /** Filter articles to only those the caller can see */
    public List<KnowledgeArticle> filterByPolicy(List<KnowledgeArticle> articles, AccessPolicy policy) {
        if (policy.isAdmin() || policy.allowedDomains().contains("*")) return articles;
        return articles.stream()
            .filter(a -> policy.allowedDomains().contains(a.getDomain()))
            .filter(a -> !policy.deniedTypes().contains(a.getType()))
            .filter(a -> "published".equals(a.getStatus()))
            .toList();
    }
}
