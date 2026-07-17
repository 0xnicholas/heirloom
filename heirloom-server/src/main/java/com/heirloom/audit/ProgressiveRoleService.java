package com.heirloom.audit;

import com.heirloom.audit.TrustScoreService.TrustScore;
import com.heirloom.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6.1: Progressive Role Service.
 * <p>
 * Auto-scales agent roles based on trust scores:
 * - TRUSTED  → full capabilities (matching agent function)
 * - NEUTRAL  → standard capabilities (default assignment)
 * - RESTRICTED → read-only + constrained query capabilities
 * - BLOCKED  → minimal capabilities (audit-only access)
 * <p>
 * Uses a scheduled task to periodically re-evaluate all agents and
 * adjust their roles accordingly. Role changes are logged for audit.
 */
@Service
public class ProgressiveRoleService {

    private static final Logger log = LoggerFactory.getLogger(ProgressiveRoleService.class);

    /** Maps trust levels to role name suffixes. */
    private static final Map<String, String> TRUST_TO_ROLE = Map.of(
        "TRUSTED", "agent-trusted",
        "NEUTRAL", "agent-standard",
        "RESTRICTED", "agent-restricted",
        "BLOCKED", "agent-blocked"
    );

    private final TrustScoreService trustScoreService;
    private final RoleRepository roleRepo;

    /** In-memory cache of last assigned role per actor, to avoid redundant updates. */
    private final ConcurrentHashMap<String, String> assignedRoles = new ConcurrentHashMap<>();

    public ProgressiveRoleService(TrustScoreService trustScoreService,
                                   RoleRepository roleRepo) {
        this.trustScoreService = trustScoreService;
        this.roleRepo = roleRepo;
    }

    /**
     * Evaluate an actor's trust score and assign an appropriate role.
     * Returns the role name assigned, or null if no change needed.
     */
    public String evaluateAndAssign(String actor) {
        TrustScore score = trustScoreService.compute(actor);
        String targetRole = TRUST_TO_ROLE.getOrDefault(score.level(), "agent-standard");

        String currentRole = assignedRoles.get(actor);
        if (targetRole.equals(currentRole)) {
            return null; // No change needed
        }

        // Ensure the role exists
        var existingRole = roleRepo.findByName(targetRole);
        if (existingRole.isEmpty()) {
            log.warn("Target role '{}' not found for actor {}, skipping assignment", targetRole, actor);
            return null;
        }

        // Assign role (in production: update actor-role mapping table)
        assignedRoles.put(actor, targetRole);

        log.info("Progressive role change for {}: {} → {} (score={:.2f}, level={})",
            actor, currentRole != null ? currentRole : "(none)",
            targetRole, score.compositeScore(), score.level());

        return targetRole;
    }

    /**
     * Scheduled task: re-evaluate all known agents every 15 minutes.
     * In Phase 6.1, this is a no-op without an actor index.
     * Production would iterate over active agents from the session store.
     */
    @Scheduled(fixedRate = 900_000) // 15 minutes
    public void scheduledEvaluation() {
        log.debug("Scheduled trust evaluation cycle (no-op without actor index)");
    }

    /**
     * Get the currently assigned role for an actor.
     */
    public String getAssignedRole(String actor) {
        return assignedRoles.get(actor);
    }

    /**
     * Get the recommended role level for an actor without assigning it.
     */
    public String recommendRole(String actor) {
        TrustScore score = trustScoreService.compute(actor);
        return TRUST_TO_ROLE.getOrDefault(score.level(), "agent-standard");
    }

    /**
     * Get the current trust score for an actor.
     */
    public TrustScore getTrustScore(String actor) {
        return trustScoreService.compute(actor);
    }
}
