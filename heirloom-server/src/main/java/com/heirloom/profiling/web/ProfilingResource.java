package com.heirloom.profiling.web;

import com.heirloom.core.profiling.ProfileReport;
import com.heirloom.core.profiling.ProfilingService;
import com.heirloom.metadata.domain.ColumnProfileEntity;
import com.heirloom.metadata.repository.ColumnProfileJpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/v1/profiling")
public class ProfilingResource {

    private final ProfilingService profilingService;
    private final ColumnProfileJpaRepository profileRepo;

    public ProfilingResource(ProfilingService profilingService, ColumnProfileJpaRepository profileRepo) {
        this.profilingService = profilingService;
        this.profileRepo = profileRepo;
    }

    @PostMapping("/tables/{tableFQN}")
    public ResponseEntity<ProfileReport> trigger(@PathVariable String tableFQN) {
        ProfileReport report = profilingService.profile(tableFQN);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/tables/{tableFQN}")
    public ProfileReport getLatest(@PathVariable String tableFQN) {
        return profilingService.profile(tableFQN);
    }

    @GetMapping("/tables/{tableFQN}/history")
    public List<ColumnProfileEntity> history(@PathVariable String tableFQN) {
        return profileRepo.findByTableFQNOrderByProfiledAtDesc(tableFQN);
    }
}
