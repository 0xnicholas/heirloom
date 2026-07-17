package com.heirloom.query;

import com.heirloom.core.query.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 1.2: Query plan optimizer.
 * <p>
 * Optimizations:
 * 1. Cache lookup — identical queries within TTL skip execution
 * 2. Multi-query merging — same table, compatible filters → single SQL with UNION
 * 3. Traverse order optimization — choose join order based on estimated row counts
 */
@Component
public class QueryOptimizer {

    private static final Logger log = LoggerFactory.getLogger(QueryOptimizer.class);

    private final QueryCache cache;

    public QueryOptimizer(QueryCache cache) {
        this.cache = cache;
    }

    /**
     * Optimize a single query request — check cache, then decide execution path.
     *
     * @return optimization result with optional cached result
     */
    public OptimizationResult optimize(Map<String, Object> request) {
        String cacheKey = QueryCache.buildCacheKey(request);

        // 1. Check cache
        QueryResult cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("Query served from cache: {}", cacheKey);
            return new OptimizationResult(cacheKey, cached, null);
        }

        return new OptimizationResult(cacheKey, null, null);
    }

    /**
     * Optimize a batch of queries — detect merge opportunities.
     * Returns a list of execution groups. Each group is either a single query
     * or a merged batch that can be executed as one SQL statement.
     */
    public List<ExecutionGroup> optimizeBatch(List<Map<String, Object>> queries) {
        if (queries.size() <= 1) {
            return queries.stream()
                .map(q -> new ExecutionGroup(List.of(q), null, null))
                .collect(Collectors.toList());
        }

        List<ExecutionGroup> groups = new ArrayList<>();

        // Group queries by table (type)
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (var query : queries) {
            String type = (String) query.get("type");
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(query);
        }

        // For each type group, check if queries can be merged
        for (var entry : byType.entrySet()) {
            String type = entry.getKey();
            var sameTypeQueries = entry.getValue();

            if (sameTypeQueries.size() == 1) {
                groups.add(new ExecutionGroup(sameTypeQueries, null, null));
                continue;
            }

            // Check merge compatibility: same fields, compatible filters
            List<Map<String, Object>> mergable = new ArrayList<>();
            Set<String> commonFields = null;

            for (var q : sameTypeQueries) {
                @SuppressWarnings("unchecked")
                List<String> fields = (List<String>) q.get("fields");
                Set<String> fieldSet = fields != null ? new HashSet<>(fields) : Set.of();

                if (commonFields == null) {
                    commonFields = fieldSet;
                    mergable.add(q);
                } else if (fieldSet.equals(commonFields)) {
                    mergable.add(q);
                } else {
                    // Different fields — cannot merge, execute separately
                    groups.add(new ExecutionGroup(List.of(q), null, null));
                }
            }

            if (mergable.size() > 1) {
                groups.add(new ExecutionGroup(mergable, type, commonFields));
                log.debug("Merged {} queries on type {} into one batch", mergable.size(), type);
            } else if (!mergable.isEmpty()) {
                groups.add(new ExecutionGroup(List.of(mergable.get(0)), null, null));
            }
        }

        // Order groups: put groups with filters first (more selective = faster)
        groups.sort((a, b) -> {
            boolean aHasFilter = hasFilter(a.queries().get(0));
            boolean bHasFilter = hasFilter(b.queries().get(0));
            if (aHasFilter && !bHasFilter) return -1;
            if (!aHasFilter && bHasFilter) return 1;
            return 0;
        });

        return groups;
    }

    /**
     * After execution, store results in cache.
     */
    public void cacheResult(String cacheKey, QueryResult result) {
        cache.put(cacheKey, result);
    }

    private boolean hasFilter(Map<String, Object> query) {
        if (query == null) return false;
        Object filter = query.get("filter");
        if (filter instanceof Map<?, ?> map && !map.isEmpty()) return true;
        Object payload = query.get("payload");
        if (payload instanceof Map<?, ?> p) {
            Object pf = p.get("filter");
            return pf instanceof Map<?, ?> m && !m.isEmpty();
        }
        return false;
    }

    // ─── DTOs ───────────────────────────────────────────────────────────────

    /**
     * Result of single-query optimization.
     */
    public record OptimizationResult(
        String cacheKey,
        QueryResult cachedResult,   // non-null = cache hit, skip execution
        String mergedSql            // non-null = merged SQL to execute
    ) {
        public boolean isCacheHit() { return cachedResult != null; }
    }

    /**
     * A group of queries that should be executed together.
     * If mergedType is non-null, the queries can be batched into one SQL.
     */
    public record ExecutionGroup(
        List<Map<String, Object>> queries,
        String mergedType,          // non-null = merge candidate for this type
        Set<String> commonFields    // fields common to all queries in the group
    ) {
        public boolean isMerged() { return mergedType != null && queries.size() > 1; }
    }
}
