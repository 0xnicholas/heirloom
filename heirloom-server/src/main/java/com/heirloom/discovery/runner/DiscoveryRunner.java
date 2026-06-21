package com.heirloom.discovery.runner;

import com.heirloom.discovery.extractor.SchemaExtractor;
import com.heirloom.discovery.extractor.postgres.PostgresSchemaExtractor;
import com.heirloom.discovery.model.RawSchema;
import com.heirloom.discovery.topology.DiscoveryContext;
import com.heirloom.discovery.topology.DiscoveryNode;
import com.heirloom.discovery.topology.DiscoveryStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.*;

public class DiscoveryRunner {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryRunner.class);
    private final SchemaExtractor extractor;
    private final DiscoveryContext context;
    private final DiscoveryNode root;

    public DiscoveryRunner(SchemaExtractor extractor) {
        this.extractor = extractor;
        this.context = new DiscoveryContext();
        this.root = buildTopology();
    }

    private DiscoveryNode buildTopology() {
        return DiscoveryNode.builder("root")
            .producer("getServices")
            .stages(List.of(DiscoveryStage.of("yieldSourceMetadata", "source")))
            .children("schema")
            .build();
    }

    public RawSchema run() {
        context.setSource("postgres-extractor", "postgresql");
        processNode(root);
        return context.buildRawSchema();
    }

    private void processNode(DiscoveryNode node) {
        log.debug("Processing node: {}", node.name());

        List<?> items = invokeProducer(node.producer());
        if (items == null) items = List.of();

        for (Object item : items) {
            for (DiscoveryStage stage : node.stages()) {
                invokeStage(stage, item);
            }
            for (String childName : node.children()) {
                // Simplified: single child chain (root → schema)
                processNode(DiscoveryNode.builder(childName).producer("getSchemaNames").stages(List.of()).build());
            }
        }
    }

    private List<?> invokeProducer(String methodName) {
        try {
            Method m = PostgresSchemaExtractor.class.getMethod(methodName);
            return (List<?>) m.invoke(extractor);
        } catch (NoSuchMethodException e) {
            // Producer not available on this extractor — skip
            return List.of();
        } catch (Exception e) {
            log.error("Producer {} failed: {}", methodName, e.getMessage());
            return List.of();
        }
    }

    private void invokeStage(DiscoveryStage stage, Object item) {
        log.debug("Stage: {}", stage.processor());
    }
}
