package com.heirloom.query;

import com.heirloom.core.query.*;
import com.heirloom.duckdb.DuckDbNaming;
import com.heirloom.duckdb.DuckDbRawStore;
import com.heirloom.duckdb.DuckDbSyncService;
import com.heirloom.duckdb.FreshnessChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryRouter.class);

    private final SemanticExecutor semantic;
    private final DuckDbRawStore duckDb;
    private final FreshnessChecker freshness;
    private final DuckDbSyncService syncService;
    private final RawQueryAuthorizer authorizer;
    private final QueryOptimizer optimizer;
    private final QueryCache cache;

    public QueryRouter(SemanticExecutor semantic, DuckDbRawStore duckDb,
                       FreshnessChecker freshness, DuckDbSyncService syncService,
                       RawQueryAuthorizer authorizer,
                       QueryOptimizer optimizer, QueryCache cache) {
        this.semantic = semantic;
        this.duckDb = duckDb;
        this.freshness = freshness;
        this.syncService = syncService;
        this.authorizer = authorizer;
        this.optimizer = optimizer;
        this.cache = cache;
    }

    public RouteDecision decide(QueryRequest req) {
        if (req.mode() != null && req.mode() != QueryMode.AUTO) {
            return new RouteDecision(req.mode(), req.payload(), req.rawTable(), req.rawSql(),
                req.resource(), req.drillDown(), List.of());
        }
        if (req.rawTable() != null || req.rawSql() != null) {
            return new RouteDecision(QueryMode.RAW, null, req.rawTable(), req.rawSql(),
                null, null, List.of());
        }
        if (req.resource() != null && req.drillDown() != null) {
            return new RouteDecision(QueryMode.HYBRID, null, null, null,
                req.resource(), req.drillDown(), List.of(
                    new RouteStep("semantic", null, Map.of()),
                    new RouteStep("duckdb", null, Map.of())
                ));
        }
        if (req.payload() != null && req.payload().type() != null) {
            return new RouteDecision(QueryMode.SEMANTIC, req.payload(), null, null,
                null, null, List.of());
        }
        throw new IllegalArgumentException(
            "Cannot route query: provide mode, rawTable, payload.type, or resource+drillDown");
    }

    public QueryResult execute(QueryRequest req) throws Exception {
        RouteDecision decision = decide(req);

        // Phase 1.2: Check query cache
        Map<String, Object> requestMap = buildRequestMap(decision);
        var optResult = optimizer.optimize(requestMap);
        if (optResult.isCacheHit()) {
            log.debug("Cache hit for query: {}", optResult.cacheKey());
            return optResult.cachedResult();
        }

        long start = System.currentTimeMillis();
        QueryResult result = switch (decision.mode()) {
            case RAW:      yield executeRaw(decision, start);
            case HYBRID:   yield executeHybrid(decision, start);
            case SEMANTIC:
            case AUTO:     yield executeSemantic(decision, start);
        };

        // Cache the result
        optimizer.cacheResult(optResult.cacheKey(), result);
        return result;
    }

    private Map<String, Object> buildRequestMap(RouteDecision d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", d.mode().name());
        if (d.payload() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", d.payload().type());
            p.put("filter", d.payload().filter());
            p.put("fields", d.payload().fields());
            map.put("payload", p);
        }
        if (d.rawTable() != null) map.put("rawTable", d.rawTable());
        if (d.rawSql() != null) map.put("rawSql", d.rawSql());
        return map;
    }

    private QueryResult executeSemantic(RouteDecision d, long start) throws Exception {
        Map<String, Object> mapPayload = payloadToMap(d.payload());
        var semResult = semantic.execute(mapPayload, "system");
        long elapsed = System.currentTimeMillis() - start;
        return new QueryResult(
            semResult.rows(), semResult.rows().size(), QueryMode.SEMANTIC,
            true, elapsed, Map.of(), Map.of()
        );
    }

    private QueryResult executeRaw(RouteDecision d, long start) {
        String tableFQN = d.rawTable();
        if (!freshness.isFresh(tableFQN)) {
            try {
                syncService.sync(tableFQN);
            } catch (Exception e) {
                log.warn("Sync failed for {}, using stale data", tableFQN, e);
            }
        }
        String sql = d.rawSql() != null
            ? d.rawSql()
            : "SELECT * FROM \"" + DuckDbNaming.toDuckDbName(tableFQN) + "\" LIMIT 1000";
        authorizer.check(tableFQN, sql);
        List<Map<String, Object>> rows = duckDb.query(sql);
        long elapsed = System.currentTimeMillis() - start;
        return new QueryResult(
            rows, rows.size(), QueryMode.RAW,
            freshness.isFresh(tableFQN), elapsed, Map.of(), Map.of()
        );
    }

    private QueryResult executeHybrid(RouteDecision d, long start) throws Exception {
        Map<String, Object> mapPayload = Map.of(
            "type", d.resource().type(),
            "rid", d.resource().rid(),
            "fields", d.resource().fields() == null ? List.of() : d.resource().fields()
        );
        var semResult = semantic.execute(mapPayload, "system");
        Map<String, Object> bindings = semResult.rows().isEmpty()
            ? Map.of()
            : new HashMap<>(semResult.rows().get(0));

        String tableFQN = d.drillDown().rawTable();
        if (!freshness.isFresh(tableFQN)) {
            try { syncService.sync(tableFQN); }
            catch (Exception e) { log.warn("Sync failed for {}, using stale data", tableFQN, e); }
        }

        String resolvedSql = resolveBindings(d.drillDown().rawSql(), bindings);
        authorizer.check(tableFQN, resolvedSql);

        List<Map<String, Object>> rows = duckDb.query(resolvedSql);
        long elapsed = System.currentTimeMillis() - start;
        return new QueryResult(
            rows, rows.size(), QueryMode.HYBRID,
            freshness.isFresh(tableFQN), elapsed, Map.of(), bindings
        );
    }

    private String resolveBindings(String sql, Map<String, Object> bindings) {
        for (var entry : bindings.entrySet()) {
            String placeholder = ":" + entry.getKey();
            String value = entry.getValue() == null
                ? "NULL"
                : entry.getValue().toString().replace("'", "''");
            sql = sql.replace(placeholder, "'" + value + "'");
        }
        return sql;
    }

    private Map<String, Object> payloadToMap(QueryPayload p) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", p.type());
        m.put("filter", p.filter());
        m.put("fields", p.fields());
        return m;
    }
}
