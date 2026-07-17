package com.heirloom.query;

import com.heirloom.core.query.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1.2: Query result cache.
 * Caches identical query results within a configurable TTL.
 * Uses the serialized request JSON as cache key.
 * Thread-safe with ConcurrentHashMap.
 */
@Component
public class QueryCache {

    private static final Logger log = LoggerFactory.getLogger(QueryCache.class);

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Default TTL: 30 seconds */
    private final long ttlMs;

    public QueryCache() {
        this(30_000);
    }

    public QueryCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * Get cached result if available and fresh.
     */
    public QueryResult get(String cacheKey) {
        CacheEntry entry = cache.get(cacheKey);
        if (entry == null) return null;
        if (Instant.now().isAfter(entry.expiresAt())) {
            cache.remove(cacheKey);
            log.debug("Cache entry expired: {}", cacheKey);
            return null;
        }
        log.debug("Cache hit: {}", cacheKey);
        return entry.result();
    }

    /**
     * Store result in cache.
     */
    public void put(String cacheKey, QueryResult result) {
        if (result == null) return;
        cache.put(cacheKey, new CacheEntry(result, Instant.now().plusMillis(ttlMs)));
        log.debug("Cache set: {} (TTL: {}ms)", cacheKey, ttlMs);
    }

    /**
     * Build a canonical cache key from a query request.
     * Normalizes field order, sorts filters, etc.
     */
    public static String buildCacheKey(Map<String, Object> request) {
        // Use a stable JSON representation as the key
        // Sort keys to ensure identical queries produce the same key
        return stableJson(request);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String stableJson(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            var keys = new ArrayList<>(map.keySet());
            Collections.sort((List) keys);
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var key : keys) {
                if (!first) sb.append(",");
                sb.append("\"").append(key).append("\":").append(stableJson(map.get(key)));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(stableJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return String.valueOf(value);
    }

    /**
     * Clear all cached entries.
     */
    public void clear() {
        cache.clear();
        log.info("Query cache cleared");
    }

    /**
     * Get current cache size.
     */
    public int size() {
        return cache.size();
    }

    private record CacheEntry(QueryResult result, Instant expiresAt) {}
}
