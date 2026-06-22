package com.heirloom.knowledge.sync;
import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class IndexGenerator {
    private static final String AUTO_START = "<!-- HEIRLOOM_AUTO_START: index -->";
    private static final String AUTO_END = "<!-- HEIRLOOM_AUTO_END: index -->";

    public int generate(KnowledgeArticleJpaRepository articleJpa, Path knowledgeRoot, String sourceFqn) throws IOException {
        List<KnowledgeArticle> articles = articleJpa.findBySourceFqnAndDeletedFalse(sourceFqn);
        Map<String, List<KnowledgeArticle>> byDir = articles.stream()
            .filter(a -> a.getStatus() != null && !a.getStatus().equals("draft") && !a.getStatus().equals("review"))
            .collect(Collectors.groupingBy(a -> {
                String fp = a.getFilePath();
                int idx = fp.lastIndexOf('/');
                return idx > 0 ? fp.substring(0, idx) : "";
            }));

        int generated = 0;
        for (var entry : byDir.entrySet()) {
            String dir = entry.getKey();
            List<KnowledgeArticle> dirArticles = entry.getValue();
            Map<String, List<KnowledgeArticle>> byType = dirArticles.stream()
                .collect(Collectors.groupingBy(a -> a.getType() != null ? a.getType() : "Unknown"));

            StringBuilder sb = new StringBuilder();
            sb.append(AUTO_START).append("\n");
            for (var te : byType.entrySet()) {
                sb.append("# ").append(te.getKey()).append("s\n\n");
                for (KnowledgeArticle a : te.getValue()) {
                    String fn = Path.of(a.getFilePath()).getFileName().toString();
                    String desc = a.getDescription() != null ? a.getDescription() : "";
                    sb.append("* [").append(a.getTitle()).append("](").append(fn).append(") - ").append(desc).append("\n");
                }
                sb.append("\n");
            }
            sb.append(AUTO_END).append("\n");

            Path indexPath = dir.isEmpty() ? knowledgeRoot.resolve("index.md") : knowledgeRoot.resolve(dir).resolve("index.md");
            Files.createDirectories(indexPath.getParent());
            String newBlock = sb.toString();

            if (Files.exists(indexPath)) {
                String existing = Files.readString(indexPath);
                int start = existing.indexOf(AUTO_START);
                int end = existing.indexOf(AUTO_END);
                String updated;
                if (start >= 0 && end > start) {
                    updated = existing.substring(0, start) + newBlock + existing.substring(end + AUTO_END.length());
                } else {
                    updated = existing.trim() + "\n\n" + newBlock;
                }
                if (!updated.equals(existing)) { Files.writeString(indexPath, updated); generated++; }
            } else {
                Files.writeString(indexPath, newBlock); generated++;
            }
        }
        return generated;
    }
}
