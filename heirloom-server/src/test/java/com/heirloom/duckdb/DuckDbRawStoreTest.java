package com.heirloom.duckdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbRawStoreTest {
    private DuckDbRawStore store;

    @BeforeEach
    void setUp() { store = new DuckDbRawStore("jdbc:duckdb::memory:"); }
    @AfterEach void tearDown() { store.close(); }

    @Test
    void shouldCreateTableAndQuery() {
        store.execute("CREATE TABLE test (id INTEGER, name VARCHAR)");
        store.execute("INSERT INTO test VALUES (1, 'foo')");
        var rows = store.query("SELECT * FROM test");
        assertEquals(1, rows.size());
        assertEquals("foo", rows.get(0).get("name"));
    }

    @Test
    void shouldReportTableExists() {
        store.execute("CREATE TABLE t1 (x INT)");
        assertTrue(store.tableExists("t1"));
        assertFalse(store.tableExists("t2"));
    }
}