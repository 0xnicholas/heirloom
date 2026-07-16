package com.heirloom.pipeline.stages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heirloom.core.pipeline.*;
import com.heirloom.discovery.domain.DiscoverySource;
import com.heirloom.discovery.service.DiscoveryService;
import com.heirloom.metadata.domain.TableEntity;
import com.heirloom.pipeline.persistence.PipelineResultEntity;
import com.heirloom.pipeline.persistence.PipelineResultJpaRepository;
import com.heirloom.repository.DiscoverySourceRepository;
import com.heirloom.repository.TableRepository;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class PipelineDiscoveryStage implements PipelineStage {

    private final DiscoveryService discoveryService;
    private final DiscoverySourceRepository sourceRepo;
    private final TableRepository tableRepo;
    private final PipelineResultJpaRepository resultRepo;
    private final ObjectMapper mapper;

    public PipelineDiscoveryStage(DiscoveryService discoveryService,
                                   DiscoverySourceRepository sourceRepo,
                                   TableRepository tableRepo,
                                   PipelineResultJpaRepository resultRepo,
                                   ObjectMapper mapper) {
        this.discoveryService = discoveryService;
        this.sourceRepo = sourceRepo;
        this.tableRepo = tableRepo;
        this.resultRepo = resultRepo;
        this.mapper = mapper;
    }

    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        DiscoverySource source = sourceRepo.findByFQN(ctx.sourceFqn())
            .orElseThrow(() -> new FatalFailure("source not found: " + ctx.sourceFqn()));

        var report = discoveryService.runDiscovery(source);

        String sourceFqn = source.getFullyQualifiedName();
        List<String> tableFqns = tableRepo.findAll().stream()
            .filter(t -> sourceFqn.equals(t.getDatabaseServiceFQN()))
            .map(TableEntity::getFullyQualifiedName)
            .toList();

        try {
            resultRepo.save(new PipelineResultEntity(
                ctx.runUuid(), "discovery", "discovery_report",
                mapper.writeValueAsString(report)));
        } catch (Exception e) {
            throw new RuntimeException("failed to persist discovery result", e);
        }

        if (tableFqns.isEmpty()) {
            throw new RecoverableFailure("discovery produced no tables for " + sourceFqn);
        }

        return new SchemaDiscovered(
            tableFqns, tableFqns.size(),
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), Instant.now(), 1, "{}");
    }
}