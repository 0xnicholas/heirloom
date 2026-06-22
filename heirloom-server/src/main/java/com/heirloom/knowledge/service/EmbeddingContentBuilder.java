package com.heirloom.knowledge.service;
import com.heirloom.knowledge.domain.KnowledgeArticle;

public class EmbeddingContentBuilder {
    private static final int MAX_BODY_LEN = 1000;

    public String build(KnowledgeArticle article) {
        StringBuilder sb = new StringBuilder();
        if (article.getTitle() != null) sb.append("Title: ").append(article.getTitle()).append(". ");
        if (article.getType() != null) sb.append("Type: ").append(article.getType()).append(". ");
        if (article.getDescription() != null) sb.append("Description: ").append(article.getDescription()).append(". ");
        if (article.getBody() != null) {
            String body = article.getBody().length() > MAX_BODY_LEN
                ? article.getBody().substring(0, MAX_BODY_LEN)
                : article.getBody();
            sb.append(body);
        }
        return sb.toString().strip();
    }
}
