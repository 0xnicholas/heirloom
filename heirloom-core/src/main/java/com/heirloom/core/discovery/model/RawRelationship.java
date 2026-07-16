package com.heirloom.core.discovery.model;

import java.util.List;

public record RawRelationship(String sourceTable, List<String> sourceColumns,
                               String targetTable, List<String> targetColumns, String deleteRule) {}
