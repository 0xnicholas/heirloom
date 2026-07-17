package com.heirloom.audit.web;

import com.heirloom.audit.ProgressiveRoleService;
import com.heirloom.audit.TrustScoreService.TrustScore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Phase 6.1: Trust & Progressive Role REST endpoints.
 */
@RestController
@RequestMapping("/v1/trust")
public class TrustResource {

    private final ProgressiveRoleService progressiveRole;

    public TrustResource(ProgressiveRoleService progressiveRole) {
        this.progressiveRole = progressiveRole;
    }

    /**
     * Get trust score and recommended role for an actor.
     */
    @GetMapping("/{actor}")
    public ResponseEntity<Map<String, Object>> getTrust(@PathVariable String actor) {
        TrustScore score = progressiveRole.getTrustScore(actor);
        String recommended = progressiveRole.recommendRole(actor);
        String assigned = progressiveRole.getAssignedRole(actor);

        return ResponseEntity.ok(Map.of(
            "actor", actor,
            "compositeScore", score.compositeScore(),
            "level", score.level(),
            "factors", Map.of(
                "denial", score.denialFactor(),
                "action", score.actionFactor(),
                "recency", score.recencyFactor(),
                "knowledge", score.knowledgeFactor()
            ),
            "recommendedRole", recommended,
            "assignedRole", assigned != null ? assigned : "",
            "totalEvents", score.totalEvents()
        ));
    }

    /**
     * Evaluate and assign a progressive role for an actor.
     */
    @PostMapping("/{actor}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable String actor) {
        String assignedRole = progressiveRole.evaluateAndAssign(actor);
        TrustScore score = progressiveRole.getTrustScore(actor);

        return ResponseEntity.ok(Map.of(
            "actor", actor,
            "compositeScore", score.compositeScore(),
            "level", score.level(),
            "assignedRole", assignedRole != null ? assignedRole : "(unchanged)",
            "totalEvents", score.totalEvents()
        ));
    }
}
