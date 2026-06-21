package com.heirloom.discovery.model;

import java.util.List;

public record RawSchema(String sourceId, String sourceType, List<RawTable> tables,
                         List<RawRelationship> relationships, String contentHash) {}
