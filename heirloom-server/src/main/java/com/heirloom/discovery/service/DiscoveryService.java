package com.heirloom.discovery.service;

import com.heirloom.discovery.domain.DiscoveryReport;
import com.heirloom.discovery.domain.DiscoverySource;
import com.heirloom.discovery.extractor.DiscoveryConfig;
import com.heirloom.discovery.extractor.SchemaExtractor;
import com.heirloom.discovery.extractor.postgres.PostgresSchemaExtractor;
import com.heirloom.discovery.inference.InferencePipeline;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.discovery.model.RawSchema;
import com.heirloom.discovery.model.RawTable;
import com.heirloom.discovery.runner.DiscoveryRunner;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    private final TypeRepository typeRepo;
    private final ProposalRepository proposalRepo;
    private final MappingRuleRepository mappingRepo;
    private final InferencePipeline inference;

    private final TableRepository tableRepo;

    public DiscoveryService(TypeRepository typeRepo, ProposalRepository proposalRepo,
                           MappingRuleRepository mappingRepo, TableRepository tableRepo) {
        this.typeRepo = typeRepo;
        this.proposalRepo = proposalRepo;
        this.mappingRepo = mappingRepo;
        this.tableRepo = tableRepo;
        this.inference = new InferencePipeline();
    }

    public DiscoveryReport runDiscovery(DiscoverySource source) {
        DiscoveryReport report = DiscoveryReport.start(source);

        try {
            DiscoveryConfig config = DiscoveryConfig.fromJson(source.getConnectionConfig(), source.getSourceType());
            PostgresSchemaExtractor extractor = new PostgresSchemaExtractor();
            extractor.prepare(config);

            if (!extractor.testConnection()) {
                report.setStatus("FAILED");
                return report;
            }

            // Phase 1: Extract
            DiscoveryRunner runner = new DiscoveryRunner(extractor, config);
            RawSchema schema = runner.run();

            report.setTablesScanned(schema.tables().size());

            // Phase 2: Infer
            List<ResourceTypeProposal> proposals = inference.infer(schema);
            report.setProposalsGenerated(proposals.size());
            report.setMetadataCreated(schema.tables().size());

            int registered = 0;
            for (ResourceTypeProposal p : proposals) {
                try {
                    if (p.isHighConfidence()) {
                        typeRepo.create(p.toResourceType());
                        registered++;
                    } else {
                        proposalRepo.create(p.toProposal());
                    }
                } catch (Exception e) {
                    log.warn("Failed to register proposal for {}", p.proposedTypeName(), e);
                }
            }
            report.setProposalsRegistered(registered);
            report.setStatus("SUCCESS");

            // Post-process: stale entity detection
            detectStaleEntities(source, schema);

        } catch (Exception e) {
            log.error("Discovery failed", e);
            report.setStatus("FAILED");
        }

        return report;
    }

    private void detectStaleEntities(DiscoverySource source, RawSchema schema) {
        // Collect FQNs from current scan
        Set<String> discoveredFQNs = schema.tables().stream()
            .map(t -> source.getFullyQualifiedName() + "." + t.schemaName() + "." + t.tableName())
            .collect(Collectors.toSet());

        // Find previously registered tables for this source that are no longer present
        // In Phase 0, we don't have TableRepository wired here — skip for now.
        // Phase 1: wire TableRepository and compare FQN sets, soft-delete stale entities.
        log.debug("Stale entity detection: {} tables discovered", discoveredFQNs.size());
    }
}
