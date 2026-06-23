package com.heirloom.schema.web;

import com.heirloom.schema.domain.OntologyBranch;
import com.heirloom.schema.service.BranchService;
import com.heirloom.schema.service.BranchService.MergeReport;
import com.heirloom.schema.service.BranchService.MergeResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Phase 4.1 Ontology Branching — REST surface.
 *
 * <pre>
 *   POST   /v1/branches                         — create branch from current main
 *   GET    /v1/branches                         — list all branches
 *   GET    /v1/branches/{name}                  — get one branch
 *   DELETE /v1/branches/{name}                  — close branch (discard clones)
 *   POST   /v1/branches/{name}/merge/preview    — classify conflicts
 *   POST   /v1/branches/{name}/merge            — apply (requires resolutions for any conflicts)
 * </pre>
 */
@RestController
@RequestMapping("/v1/branches")
public class BranchResource {

    private final BranchService branchService;

    public BranchResource(BranchService branchService) {
        this.branchService = branchService;
    }

    @PostMapping
    public ResponseEntity<OntologyBranch> create(
            @RequestBody CreateBranchRequest body,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-User", required = false) String user) {
        String caller = agentId != null ? "agent:" + agentId
                : user != null ? "user:" + user : "system";
        return ResponseEntity.ok(branchService.createBranch(
                body.name(), caller, body.description()));
    }

    @GetMapping
    public ResponseEntity<List<OntologyBranch>> list() {
        return ResponseEntity.ok(branchService.listBranches());
    }

    @GetMapping("/{name}")
    public ResponseEntity<OntologyBranch> get(@PathVariable String name) {
        return branchService.getBranch(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> close(@PathVariable String name) {
        branchService.closeBranch(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{name}/merge/preview")
    public ResponseEntity<MergeReport> previewMerge(@PathVariable String name) {
        return ResponseEntity.ok(branchService.previewMerge(name));
    }

    @PostMapping("/{name}/merge")
    public ResponseEntity<MergeResult> merge(
            @PathVariable String name,
            @RequestBody(required = false) MergeRequest body,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-User", required = false) String user) {
        String caller = agentId != null ? "agent:" + agentId
                : user != null ? "user:" + user : "system";
        Map<String, String> resolutions = body != null && body.resolutions() != null
                ? body.resolutions() : Map.of();
        return ResponseEntity.ok(branchService.applyMerge(name, resolutions, caller));
    }

    public record CreateBranchRequest(String name, String description) {}
    public record MergeRequest(Map<String, String> resolutions) {}
}