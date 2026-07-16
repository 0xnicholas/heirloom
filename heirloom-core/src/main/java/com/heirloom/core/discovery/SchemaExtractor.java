package com.heirloom.core.discovery;

import com.heirloom.core.discovery.model.RawSchema;
import java.util.Set;

public interface SchemaExtractor {
    void prepare(DiscoveryConfig config);
    boolean testConnection();
    RawSchema extract(DiscoveryConfig config);
    Set<ExtractorCapability> capabilities();

    enum ExtractorCapability { SCHEMA_METADATA, CONSTRAINTS, COLUMN_STATISTICS, LINEAGE_SQL, DESCRIPTIONS, USAGE_STATS }
}
