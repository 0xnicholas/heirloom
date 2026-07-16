package com.heirloom.duckdb;

import com.heirloom.metadata.domain.ColumnProfileEntity;
import com.heirloom.metadata.repository.ColumnProfileJpaRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FreshnessCheckerTest {
    private final DuckDbRawStore duckDb = mock(DuckDbRawStore.class);
    private final ColumnProfileJpaRepository profileRepo = mock(ColumnProfileJpaRepository.class);
    private final FreshnessChecker checker = new FreshnessChecker(duckDb, profileRepo, 5);

    @Test
    void shouldReturnNotFresh_WhenTableMissing() {
        when(duckDb.tableExists("_raw_public_orders")).thenReturn(false);
        assertFalse(checker.isFresh("public.orders"));
    }

    @Test
    void shouldReturnFresh_WhenProfileRecent() {
        when(duckDb.tableExists("_raw_public_orders")).thenReturn(true);
        var profile = new ColumnProfileEntity();
        profile.setProfiledAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(profileRepo.findByTableFQNAndColumnNameOrderByProfiledAtDesc("public.orders", "_row_count"))
            .thenReturn(List.of(profile));
        assertTrue(checker.isFresh("public.orders"));
    }

    @Test
    void shouldReturnNotFresh_WhenProfileStale() {
        when(duckDb.tableExists("_raw_public_orders")).thenReturn(true);
        var profile = new ColumnProfileEntity();
        profile.setProfiledAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        when(profileRepo.findByTableFQNAndColumnNameOrderByProfiledAtDesc("public.orders", "_row_count"))
            .thenReturn(List.of(profile));
        assertFalse(checker.isFresh("public.orders"));
    }

    @Test
    void shouldReturnNotFresh_WhenNoProfile() {
        when(duckDb.tableExists("_raw_public_orders")).thenReturn(true);
        when(profileRepo.findByTableFQNAndColumnNameOrderByProfiledAtDesc("public.orders", "_row_count"))
            .thenReturn(List.of());
        assertFalse(checker.isFresh("public.orders"));
    }
}
