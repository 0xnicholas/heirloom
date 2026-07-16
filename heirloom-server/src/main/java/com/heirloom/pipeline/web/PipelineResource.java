package com.heirloom.pipeline.web;

import com.heirloom.core.pipeline.PipelineStatus;
import com.heirloom.pipeline.persistence.DeadLetterEntity;
import com.heirloom.pipeline.persistence.DeadLetterJpaRepository;
import com.heirloom.pipeline.persistence.PipelineRunEntity;
import com.heirloom.pipeline.persistence.PipelineStageStatusJpaRepository;
import com.heirloom.pipeline.service.PipelineService;
import com.heirloom.pipeline.web.dto.DeadLetterResponse;
import com.heirloom.pipeline.web.dto.PipelineRunResponse;
import com.heirloom.pipeline.web.dto.TriggerPipelineRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/pipeline")
public class PipelineResource {

    private final PipelineService service;
    private final PipelineStageStatusJpaRepository stageRepo;
    private final DeadLetterJpaRepository dlqRepo;

    public PipelineResource(PipelineService service,
                             PipelineStageStatusJpaRepository stageRepo,
                             DeadLetterJpaRepository dlqRepo) {
        this.service = service;
        this.stageRepo = stageRepo;
        this.dlqRepo = dlqRepo;
    }

    @PostMapping("/runs")
    public ResponseEntity<PipelineRunResponse> trigger(@Valid @RequestBody TriggerPipelineRequest req) {
        PipelineRunEntity run = service.startRun("default", req.sourceFqn(),
            req.tableFqns(), req.effectiveTriggerType());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .header("Location", "/v1/pipeline/runs/" + run.getRunUuid())
            .body(PipelineRunResponse.from(run, stageRepo));
    }

    @GetMapping("/runs/{runUuid}")
    public PipelineRunResponse get(@PathVariable UUID runUuid) {
        return PipelineRunResponse.from(service.get(runUuid), stageRepo);
    }

    @GetMapping("/runs")
    public List<PipelineRunResponse> list(
            @RequestParam(required = false) String sourceFqn,
            @RequestParam(required = false) PipelineStatus status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.list(sourceFqn, status, Math.min(limit, 500), offset).stream()
            .map(r -> PipelineRunResponse.from(r, stageRepo))
            .toList();
    }

    @GetMapping("/dead-letter")
    public List<DeadLetterResponse> deadLetter(
            @RequestParam(required = false) String sourceFqn,
            @RequestParam(required = false) Boolean replayed,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<DeadLetterEntity> all;
        if (sourceFqn != null) {
            all = dlqRepo.findBySourceFqnOrderByFailedAtDesc(sourceFqn);
        } else if (Boolean.TRUE.equals(replayed)) {
            all = dlqRepo.findByReplayedAtIsNotNullOrderByFailedAtDesc();
        } else if (Boolean.FALSE.equals(replayed)) {
            all = dlqRepo.findByReplayedAtIsNullOrderByFailedAtDesc();
        } else {
            all = dlqRepo.findAllByOrderByFailedAtDesc();
        }
        return all.stream()
            .skip(offset).limit(Math.min(limit, 500))
            .map(DeadLetterResponse::from).toList();
    }

    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, Object>> conflict(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "conflict", "message", e.getMessage()));
    }
}