package com.heirloom.web;

import com.heirloom.discovery.domain.DiscoveryReport;
import com.heirloom.discovery.domain.DiscoverySource;
import com.heirloom.discovery.service.DiscoveryService;
import com.heirloom.repository.DiscoveryReportRepository;
import com.heirloom.repository.DiscoverySourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/v1/discovery")
public class DiscoveryResource {

    private final DiscoveryService discoveryService;
    private final DiscoverySourceRepository sourceRepo;
    private final DiscoveryReportRepository reportRepo;

    public DiscoveryResource(DiscoveryService discoveryService,
                            DiscoverySourceRepository sourceRepo,
                            DiscoveryReportRepository reportRepo) {
        this.discoveryService = discoveryService;
        this.sourceRepo = sourceRepo;
        this.reportRepo = reportRepo;
    }

    @PostMapping("/sources/{sourceFQN}/run")
    public ResponseEntity<DiscoveryReport> runDiscovery(@PathVariable String sourceFQN) {
        DiscoverySource source = sourceRepo.findByFQN(sourceFQN)
            .orElseThrow(() -> new RuntimeException("DiscoverySource not found: " + sourceFQN));
        DiscoveryReport report = discoveryService.runDiscovery(source);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/reports")
    public ResponseEntity<List<DiscoveryReport>> listReports(@RequestParam String source) {
        return ResponseEntity.ok(reportRepo.findBySourceFQN(source));
    }
}
