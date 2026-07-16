package com.heirloom.core.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public record DiscoveryConfig(String sourceType, String host, int port, String database,
                               String username, String password, String schema) {

    private static final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public static DiscoveryConfig fromJson(String json, String sourceType) {
        try {
            Map<String, Object> map = mapper.readValue(json, Map.class);
            return new DiscoveryConfig(
                sourceType,
                getString(map, "host", "localhost"),
                getInt(map, "port", 5432),
                getString(map, "database", sourceType),
                getString(map, "username", ""),
                getString(map, "password", ""),
                getString(map, "schema", "public"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DiscoveryConfig JSON", e);
        }
    }

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) return Integer.parseInt(s);
        return defaultVal;
    }
}
