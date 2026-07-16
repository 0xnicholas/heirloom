package com.heirloom.duckdb.web;

import com.heirloom.duckdb.DuckDbRawStore;
import com.heirloom.duckdb.DuckDbSyncService;
import com.heirloom.duckdb.FreshnessChecker;
import com.heirloom.duckdb.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DuckDbResource.class)
class DuckDbResourceTest {

    @Autowired MockMvc mvc;
    @MockitoBean DuckDbSyncService syncService;
    @MockitoBean FreshnessChecker freshnessChecker;
    @MockitoBean DuckDbRawStore duckDb;

    @Test
    void shouldTriggerSync() throws Exception {
        when(syncService.sync("public.orders")).thenReturn(new SyncResult("public.orders", 1000, 42));
        mvc.perform(post("/v1/duckdb/sync/public.orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tableFQN").value("public.orders"))
            .andExpect(jsonPath("$.rowCount").value(1000))
            .andExpect(jsonPath("$.durationMs").value(42));
    }

    @Test
    void shouldReturnFreshness() throws Exception {
        when(freshnessChecker.isFresh("public.orders")).thenReturn(true);
        when(freshnessChecker.lastSyncedAt("public.orders")).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(freshnessChecker.ttlDescription()).thenReturn("5 minutes");
        mvc.perform(get("/v1/duckdb/tables/public.orders/freshness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tableFQN").value("public.orders"))
            .andExpect(jsonPath("$.fresh").value(true))
            .andExpect(jsonPath("$.ttl").value("5 minutes"));
    }

    @Test
    void shouldListTables() throws Exception {
        when(duckDb.query(anyString())).thenReturn(List.of(
            Map.of("table_name", "_raw_public_orders"),
            Map.of("table_name", "_raw_public_customers")
        ));
        mvc.perform(get("/v1/duckdb/tables"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("_raw_public_orders"))
            .andExpect(jsonPath("$[1]").value("_raw_public_customers"));
    }
}