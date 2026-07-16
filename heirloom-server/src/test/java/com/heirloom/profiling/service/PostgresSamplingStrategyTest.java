package com.heirloom.profiling.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresSamplingStrategyTest {
    @Test
    void shouldSampleLargeTable() {
        var s = new PostgresSamplingStrategy(0.1);
        var sql = s.apply("public.users", 5_000_000L);
        assertTrue(sql.contains("TABLESAMPLE BERNOULLI(10)"));
    }

    @Test
    void shouldNotSampleSmallTable() {
        var s = new PostgresSamplingStrategy(0.1);
        var sql = s.apply("public.config", 100L);
        assertEquals("public.config", sql);
    }

    @Test
    void shouldNotSampleExactlyAtThreshold() {
        var s = new PostgresSamplingStrategy(0.1);
        var sql = s.apply("public.borders", 1_000_000L);
        assertEquals("public.borders", sql);
    }
}
