package com.heirloom.knowledge.service;

import java.util.Arrays;
import java.util.stream.Collectors;

public class QuerySanitizer {
    public static String toTsQuery(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw.replaceAll("[^\\w\\s]", " ").trim();
        if (cleaned.isEmpty()) return "";
        return Arrays.stream(cleaned.split("\\s+"))
            .map(w -> w + ":*")
            .collect(Collectors.joining(" & "));
    }
}
