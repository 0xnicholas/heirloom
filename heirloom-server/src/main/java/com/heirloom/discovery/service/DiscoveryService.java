package com.heirloom.discovery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.alignment.AlignmentService;
import com.heirloom.discovery.domain.DiscoveryReport;
import com.heirloom.discovery.domain.DiscoverySource;
import com.heirloom.core.discovery.DiscoveryConfig;
import com.heirloom.core.discovery.SchemaExtractor;
import com.heirloom.connector.postgres.PostgresSchemaExtractor;
import com.heirloom.discovery.inference.InferenceContext;
import com.heirloom.discovery.inference.InferencePipeline;
import com.heirloom.discovery.inference.ResourceTypeProposal;
import com.heirloom.core.discovery.model.RawSchema;
import com.heirloom.core.discovery.model.RawTable;
import com.heirloom.core.profiling.ProfileReport;
import com.heirloom.core.profiling.ProfilingService;
import com.heirloom.discovery.runner.DiscoveryRunner;
import com.heirloom.metadata.domain.LineageEntity;
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
    private final TableRepository tableRepo;
    private final InferencePipeline inference;

    private final LineageRepository lineageRepo;
    private final ProfilingService profilingService;

    public DiscoveryService(TypeRepository typeRepo, ProposalRepository proposalRepo,
                           MappingRuleRepository mappingRepo, TableRepository tableRepo,
                           LineageRepository lineageRepo, ProfilingService profilingService,
                           AlignmentService alignmentService) {
        this.typeRepo = typeRepo;
        this.proposalRepo = proposalRepo;
        this.mappingRepo = mappingRepo;
        this.tableRepo = tableRepo;
        this.lineageRepo = lineageRepo;
        this.inference = new InferencePipeline(alignmentService);
        this.profilingService = profilingService;
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

            // Phase 1b: Create metadata entities
            int metadataCreated = 0;
            for (var rawTable : schema.tables()) {
                try {
                    TableEntity table = new TableEntity();
                    table.setName(rawTable.tableName());
                    table.setDatabaseServiceFQN(source.getFullyQualifiedName());
                    table.setTableType("BASE TABLE");
                    table.setColumnsJson(toJson(rawTable.columns()));
                    table.setDescription(rawTable.comment());
                    // FQN is set by TableRepository.setFullyQualifiedName()
                    tableRepo.create(table);
                    metadataCreated++;
                } catch (Exception e) {
                    log.warn("Failed to create metadata entity for {}", rawTable.tableName(), e);
                }
            }
            report.setMetadataCreated(metadataCreated);

            // Phase 1c: Create lineage entities from FK constraints
            for (var rawTable : schema.tables()) {
                String fromFQN = source.getFullyQualifiedName() + "." + rawTable.schemaName() + "." + rawTable.tableName();
                for (var c : rawTable.constraints()) {
                    if (c.type() == com.heirloom.core.discovery.model.RawConstraint.ConstraintType.FOREIGN_KEY) {
                        LineageEntity lineage = new LineageEntity();
                        lineage.setFromEntityFQN(fromFQN);
                        lineage.setToEntityFQN(source.getFullyQualifiedName() + "." + rawTable.schemaName() + "." + c.targetTable());
                        lineage.setLineageType("table_lineage");
                        lineage.setSource("fk_inference");
                        lineage.setName(fromFQN + " → " + c.targetTable());
                        lineageRepo.create(lineage);
                    }
                }
            }

            // Phase 1d: Profile
            for (var rawTable : schema.tables()) {
                try {
                    String tableFQN = source.getFullyQualifiedName() + "." + rawTable.schemaName() + "." + rawTable.tableName();
                    ProfileReport profile = profilingService.profile(tableFQN);
                    log.debug("Profiled {}: {} columns, quality={}", tableFQN, profile.columnCount(), profile.overallQualityScore());
                } catch (Exception e) {
                    log.warn("Profiling failed for table {}: {}", rawTable.tableName(), e.getMessage());
                }
            }

            // Phase 2: Infer
            InferenceContext ctx = new InferenceContext(schema, null, null, List.of(), source.getFullyQualifiedName());
            List<ResourceTypeProposal> proposals = inference.infer(ctx);
            report.setProposalsGenerated(proposals.size());
            report.setMetadataCreated(metadataCreated);

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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }

    private void detectStaleEntities(DiscoverySource source, RawSchema schema) {
        // Collect FQNs from current scan
        Set<String> discoveredFQNs = schema.tables().stream()
            .map(t -> source.getFullyQualifiedName() + "." + t.schemaName() + "." + t.tableName())
            .collect(Collectors.toSet());

        // Find previously registered tables for this source
        List<TableEntity> existingTables = tableRepo.findAll().stream()
            .filter(t -> t.getFullyQualifiedName() != null
                && t.getFullyQualifiedName().startsWith(source.getFullyQualifiedName()))
            .toList();

        // Tables in DB but not in current scan → stale
        for (TableEntity existing : existingTables) {
            if (!discoveredFQNs.contains(existing.getFullyQualifiedName())) {
                existing.setDeleted(true);
                tableRepo.update(existing);
                log.info("Marked stale table: {}", existing.getFullyQualifiedName());
            }
        }
    }
}
