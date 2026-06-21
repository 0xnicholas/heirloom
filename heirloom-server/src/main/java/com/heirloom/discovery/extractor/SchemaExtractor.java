package com.heirloom.discovery.extractor;

import com.heirloom.discovery.model.RawSchema;
import java.util.Set;

public interface SchemaExtractor {
    void prepare(DiscoveryConfig config);
    boolean testConnection();
    RawSchema extract(DiscoveryConfig config);
    Set<ExtractorCapability> capabilities();

    enum ExtractorCapability { SCHEMA_METADATA, CONSTRAINTS, COLUMN_STATISTICS, LINEAGE_SQL, DESCRIPTIONS, USAGE_STATS }
}
