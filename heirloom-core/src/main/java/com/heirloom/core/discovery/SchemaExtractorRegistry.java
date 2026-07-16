package com.heirloom.core.discovery;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class SchemaExtractorRegistry {
    private static final Map<String, Supplier<SchemaExtractor>> factories = new ConcurrentHashMap<>();

    public static void register(String sourceType, Supplier<SchemaExtractor> factory) {
        factories.put(sourceType.toLowerCase(), factory);
    }

    public static Optional<SchemaExtractor> create(String sourceType) {
        var factory = factories.get(sourceType.toLowerCase());
        return factory != null ? Optional.of(factory.get()) : Optional.empty();
    }

    public static Set<String> supportedSourceTypes() {
        return Set.copyOf(factories.keySet());
    }
}
