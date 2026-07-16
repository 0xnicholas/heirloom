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

    public QueryRouter(SemanticExecutor semantic, DuckDbRawStore duckDb,
                       FreshnessChecker freshness, DuckDbSyncService syncService,
                       RawQueryAuthorizer authorizer) {
        this.semantic = semantic;
        this.duckDb = duckDb;
        this.freshness = freshness;
        this.syncService = syncService;
        this.authorizer = authorizer;
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
        long start = System.currentTimeMillis();
        switch (decision.mode()) {
            case RAW:      return executeRaw(decision, start);
            case HYBRID:   return executeHybrid(decision, start);
            case SEMANTIC: return executeSemantic(decision, start);
            default: throw new IllegalStateException("Unreachable: " + decision.mode());
        }
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
