package com.heirloom.duckdb;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DuckDbNamingTest {
    @Test void shouldConvertFQNToDuckDb() {
        assertEquals("_raw_prod_pg_public_orders", DuckDbNaming.toDuckDbName("prod.pg.public.orders"));
    }
    @Test void shouldConvertDuckDbToFQN() {
        assertEquals("prod.pg.public.orders", DuckDbNaming.fromDuckDbName("_raw_prod_pg_public_orders"));
    }
}