package com.heirloom.audit;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.repository.EventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Phase 6.1: Trust Score Service.
 * <p>
 * Computes a multi-factor trust score for AI agents based on historical behavior.
 * Scores range from 0.0 (untrusted) to 1.0 (fully trusted) and are used by
 * {@link ProgressiveRoleService} to auto-scale agent roles.
 * <p>
 * Factors (weighted):
 * - Denial rate (40%): lower is better
 * - Successful action completion (30%): higher is better
 * - Recency of violations (20%): recent violations reduce score more
 * - Knowledge contribution (10%): creating/improving KnowledgeArticles
 */
@Service
public class TrustScoreService {

    private static final Logger log = LoggerFactory.getLogger(TrustScoreService.class);

    private static final double DENIAL_WEIGHT = 0.40;
    private static final double ACTION_WEIGHT = 0.30;
    private static final double RECENCY_WEIGHT = 0.20;
    private static final double KNOWLEDGE_WEIGHT = 0.10;

    /** Events are considered "recent" within this window. */
    private static final Duration RECENCY_WINDOW = Duration.ofHours(24);

    /** Full trust requires at least this many events to be meaningful. */
    private static final long MIN_EVENTS_FOR_SCORE = 5;

    private final EventLogRepository eventLog;

    public TrustScoreService(EventLogRepository eventLog) {
        this.eventLog = eventLog;
    }

    /**
     * Compute a composite trust score for an actor.
     */
    public TrustScore compute(String actor) {
        Instant now = Instant.now();
        Instant last72h = now.minus(Duration.ofHours(72));
        Instant last24h = now.minus(RECENCY_WINDOW);

        long totalEvents = eventLog.actorTotalEvents(actor, last72h, now);
        if (totalEvents < MIN_EVENTS_FOR_SCORE) {
            return new TrustScore(actor, 0.5, 0.5, 0.5, 0.5, 0.0,
                "insufficient data, assigned neutral score", totalEvents);
        }

        // 1. Denial rate factor (40%)
        long denied = eventLog.actorEventCount(actor, ChangeEvent.EventType.ENTITY_DENIED, last72h, now);
        double denialRate = (double) denied / (double) totalEvents;
        double denialScore = 1.0 - Math.min(denialRate, 1.0);

        // 2. Action completion factor (30%)
        long successfulActions = totalEvents - denied;
        double actionScore = Math.min((double) successfulActions / (double) totalEvents, 1.0);

        // 3. Recency factor (20%)
        List<ChangeEvent> recentViolations = eventLog.actorActivity(actor, last24h, now).stream()
            .filter(e -> e.getEventType() == ChangeEvent.EventType.ENTITY_DENIED)
            .toList();
        double recencyScore = Math.max(0.0, 1.0 - (recentViolations.size() * 0.2));

        // 4. Knowledge contribution factor (10%)
        long knowledgeEvents = eventLog.actorActivity(actor, last72h, now).stream()
            .filter(e -> e.getEventType() == ChangeEvent.EventType.ENTITY_CREATED
                && "knowledge".equals(e.getEntityType()))
            .count();
        double knowledgeScore = Math.min(knowledgeEvents / 10.0, 1.0);

        // Composite score
        double composite = (denialScore * DENIAL_WEIGHT)
                         + (actionScore * ACTION_WEIGHT)
                         + (recencyScore * RECENCY_WEIGHT)
                         + (knowledgeScore * KNOWLEDGE_WEIGHT);

        // Determine trust level
        String level;
        if (composite >= 0.8) level = "TRUSTED";
        else if (composite >= 0.5) level = "NEUTRAL";
        else if (composite >= 0.2) level = "RESTRICTED";
        else level = "BLOCKED";

        log.debug("Trust score for {}: {:.2f} ({}) — denial={:.2f} action={:.2f} recency={:.2f} knowledge={:.2f}",
            actor, composite, level, denialScore, actionScore, recencyScore, knowledgeScore);

        return new TrustScore(actor, composite, denialScore, actionScore, recencyScore, knowledgeScore,
            level, totalEvents);
    }

    /**
     * Compute scores for all actors seen in the last 72 hours.
     */
    public List<TrustScore> computeAll() {
        // Not implemented for now — requires an actor-list index.
        // Phase 6.1++ can add this when needed.
        return List.of();
    }

    // ─── Record ─────────────────────────────────────────────────────────

    public record TrustScore(
        String actor,
        double compositeScore,
        double denialFactor,
        double actionFactor,
        double recencyFactor,
        double knowledgeFactor,
        String level,
        long totalEvents
    ) {
        // Compact constructor: derives level from composite score
        public TrustScore {
            if (level == null || level.isBlank()) {
                level = compositeScore >= 0.8 ? "TRUSTED" :
                        compositeScore >= 0.5 ? "NEUTRAL" :
                        compositeScore >= 0.2 ? "RESTRICTED" : "BLOCKED";
            }
        }
    }
}
