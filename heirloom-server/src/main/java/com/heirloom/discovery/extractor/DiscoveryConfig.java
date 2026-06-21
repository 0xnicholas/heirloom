package com.heirloom.discovery.extractor;

public record DiscoveryConfig(String sourceType, String host, int port, String database,
                               String username, String password, String schema) {
    public static DiscoveryConfig fromJson(String json, String sourceType) {
        // Simplified: parse JSON or use defaults. Full impl in PostgresSchemaExtractor.
        return new DiscoveryConfig(sourceType, "localhost", 5432, "test", "user", "pass", "public");
    }
}
