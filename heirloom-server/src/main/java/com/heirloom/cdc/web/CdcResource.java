package com.heirloom.cdc.web;

import com.heirloom.cdc.domain.CdcSource;
import com.heirloom.cdc.repository.CdcSourceRepository;
import com.heirloom.cdc.service.CdcEngineManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/cdc/sources")
public class CdcResource {

    private final CdcSourceRepository sourceRepo;
    private final CdcEngineManager engineManager;

    public CdcResource(CdcSourceRepository sourceRepo, CdcEngineManager engineManager) {
        this.sourceRepo = sourceRepo;
        this.engineManager = engineManager;
    }

    @PostMapping
    public ResponseEntity<CdcSource> create(@RequestBody CdcSource source) {
        source.setStatus("STOPPED");
        return ResponseEntity.status(201).body(sourceRepo.save(source));
    }

    @GetMapping
    public ResponseEntity<List<CdcSource>> list() {
        return ResponseEntity.ok(sourceRepo.findAll());
    }

    @GetMapping("/{name}")
    public ResponseEntity<CdcSource> get(@PathVariable String name) {
        return sourceRepo.findByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) {
        // Stop engine first if running
        if (engineManager.isRunning(name)) {
            engineManager.stop(name);
        }
        sourceRepo.findByName(name).ifPresent(sourceRepo::delete);
        // Note: publication and slot cleanup on source PG must be done manually
        // or via a separate cleanup API call (future)
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/start")
    public ResponseEntity<Map<String, String>> start(@PathVariable String name) {
        engineManager.start(name);
        return ResponseEntity.ok(Map.of("status", "STARTING",
                "message", "Replication slot created, streaming begins"));
    }

    @PostMapping("/{name}/stop")
    public ResponseEntity<Map<String, String>> stop(@PathVariable String name) {
        engineManager.stop(name);
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    @GetMapping("/{name}/status")
    public ResponseEntity<?> status(@PathVariable String name) {
        CdcEngineManager.CdcStatus s = engineManager.getStatus(name);
        if (s == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(s);
    }
}
