package com.heirloom.knowledge.service;

import com.heirloom.knowledge.domain.KnowledgeArticle;
import com.heirloom.knowledge.repository.KnowledgeArticleJpaRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KnowledgeGraphService {

    private final KnowledgeArticleJpaRepository jpa;

    public KnowledgeGraphService(KnowledgeArticleJpaRepository jpa) { this.jpa = jpa; }

    public record GraphResult(List<KnowledgeArticle> nodes, List<Edge> edges, int maxDepthReached) {}
    public record Edge(String from, String to, String type, String label) {}

    /** BFS traversal: from startFqn, follow references up to maxDepth hops. */
    public GraphResult traverse(String startFqn, int maxDepth) {
        Set<String> visited = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> depths = new HashMap<>();

        queue.add(startFqn);
        depths.put(startFqn, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depths.get(current);
            if (depth > maxDepth || visited.contains(current)) continue;
            visited.add(current);

            Optional<KnowledgeArticle> article = jpa.findByFullyQualifiedName(current);
            if (article.isEmpty()) continue;

            for (var ref : article.get().getReferences()) {
                edges.add(new Edge(current, ref.fqn(), "ENTITY_REF", ref.label()));
                if (!visited.contains(ref.fqn()) && depth < maxDepth) {
                    queue.add(ref.fqn());
                    depths.put(ref.fqn(), depth + 1);
                }
            }
        }

        List<KnowledgeArticle> nodes = visited.stream()
            .map(jpa::findByFullyQualifiedName)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .toList();

        return new GraphResult(nodes, edges, depths.values().stream().max(Integer::compare).orElse(0));
    }
}
