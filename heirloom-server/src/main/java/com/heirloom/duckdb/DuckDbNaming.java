package com.heirloom.duckdb;

public final class DuckDbNaming {
    private DuckDbNaming() {}

    public static String toDuckDbName(String fqn) {
        return "_raw_" + fqn.replace('.', '_');
    }

    public static String fromDuckDbName(String duckName) {
        if (!duckName.startsWith("_raw_")) {
            throw new IllegalArgumentException("Not a DuckDB raw name: " + duckName);
        }
        return duckName.substring("_raw_".length()).replace('_', '.');
    }
}