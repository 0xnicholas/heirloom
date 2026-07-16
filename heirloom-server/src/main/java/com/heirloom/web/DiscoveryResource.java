package com.heirloom.web;

import com.heirloom.core.pipeline.PipelineTriggerType;
import com.heirloom.discovery.domain.DiscoveryReport;
import com.heirloom.discovery.domain.DiscoverySource;
import com.heirloom.discovery.service.DiscoveryService;
import com.heirloom.pipeline.persistence.PipelineRunJpaRepository;
import com.heirloom.pipeline.persistence.PipelineStageStatusJpaRepository;
import com.heirloom.pipeline.service.PipelineService;
import com.heirloom.pipeline.web.dto.PipelineRunResponse;
import com.heirloom.repository.DiscoveryReportRepository;
import com.heirloom.repository.DiscoverySourceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/v1/discovery")
public class DiscoveryResource {

    private final DiscoveryService discoveryService;
    private final DiscoverySourceRepository sourceRepo;
    private final DiscoveryReportRepository reportRepo;
    private final PipelineService pipelineService;
    private final PipelineStageStatusJpaRepository stageRepo;

    public DiscoveryResource(DiscoveryService discoveryService,
                            DiscoverySourceRepository sourceRepo,
                            DiscoveryReportRepository reportRepo,
                            PipelineService pipelineService,
                            PipelineStageStatusJpaRepository stageRepo) {
        this.discoveryService = discoveryService;
        this.sourceRepo = sourceRepo;
        this.reportRepo = reportRepo;
        this.pipelineService = pipelineService;
        this.stageRepo = stageRepo;
    }

    @PostMapping("/sources/{sourceFQN}/run")
    public ResponseEntity<PipelineRunResponse> run(@PathVariable String sourceFQN,
                                                    @RequestParam(defaultValue = "true") boolean profile) {
        DiscoverySource source = sourceRepo.findByFQN(sourceFQN)
            .orElseThrow(() -> new RuntimeException("DiscoverySource not found: " + sourceFQN));
        var run = pipelineService.startRun("default", sourceFQN, List.of(), PipelineTriggerType.DISCOVERY_AUTO);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .header("Location", "/v1/pipeline/runs/" + run.getRunUuid())
            .body(PipelineRunResponse.from(run, stageRepo));
    }

    @Deprecated
    @PostMapping("/sources/{sourceFQN}/run-sync")
    public DiscoveryReport runSync(@PathVariable String sourceFQN,
                                    @RequestParam(defaultValue = "true") boolean profile) {
        DiscoverySource source = sourceRepo.findByFQN(sourceFQN)
            .orElseThrow(() -> new RuntimeException("DiscoverySource not found: " + sourceFQN));
        return discoveryService.runDiscovery(source);
    }

    @GetMapping("/reports")
    public List<DiscoveryReport> listReports(@RequestParam String source) {
        return reportRepo.findBySourceFQN(source);
    }
}