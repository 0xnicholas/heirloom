package com.heirloom.query;

import com.heirloom.core.query.*;
import com.heirloom.duckdb.DuckDbRawStore;
import com.heirloom.duckdb.DuckDbSyncService;
import com.heirloom.duckdb.FreshnessChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryRouterTest {
    @Mock SemanticExecutor semantic;
    @Mock DuckDbRawStore duckDb;
    @Mock FreshnessChecker freshness;
    @Mock DuckDbSyncService sync;
    @Mock RawQueryAuthorizer authorizer;
    @InjectMocks QueryRouter router;

    @Test void shouldRouteToRaw_WhenModeIsRaw() throws Exception {
        var req = new QueryRequest(QueryMode.RAW, null, "public.orders", "SELECT * FROM {table} LIMIT 10", null, null);
        when(freshness.isFresh("public.orders")).thenReturn(true);
        when(duckDb.query(anyString())).thenReturn(List.of());
        router.execute(req);
        verify(duckDb).query(anyString());
    }

    @Test void shouldRouteToSemantic_WhenPayloadHasType() throws Exception {
        var payload = new QueryPayload("Customer", Map.of("tier", Map.of("$eq", "Gold")), null);
        var req = new QueryRequest(QueryMode.AUTO, payload, null, null, null, null);
        when(semantic.execute(anyMap(), anyString())).thenReturn(new SemanticExecutor.SemanticResult(List.of(), 0));
        router.execute(req);
        verify(semantic).execute(anyMap(), anyString());
    }

    @Test void shouldSyncBeforeQuery_WhenNotFresh() throws Exception {
        var req = new QueryRequest(QueryMode.RAW, null, "public.orders", "SELECT * FROM {table} LIMIT 10", null, null);
        when(freshness.isFresh("public.orders")).thenReturn(false);
        when(duckDb.query(anyString())).thenReturn(List.of());
        router.execute(req);
        verify(sync).sync("public.orders");
        verify(duckDb).query(anyString());
    }
}
