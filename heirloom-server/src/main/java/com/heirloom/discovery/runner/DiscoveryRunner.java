package com.heirloom.discovery.runner;

import com.heirloom.discovery.extractor.DiscoveryConfig;
import com.heirloom.discovery.extractor.SchemaExtractor;
import com.heirloom.discovery.model.RawSchema;
import com.heirloom.discovery.topology.DiscoveryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryRunner {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryRunner.class);
    private final SchemaExtractor extractor;
    private final DiscoveryConfig config;

    public DiscoveryRunner(SchemaExtractor extractor, DiscoveryConfig config) {
        this.extractor = extractor;
        this.config = config;
    }

    public RawSchema run() {
        log.info("Starting discovery for {} at {}:{}", config.sourceType(), config.host(), config.port());
        return extractor.extract(config);
    }
}
