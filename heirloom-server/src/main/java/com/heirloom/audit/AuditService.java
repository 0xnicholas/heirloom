package com.heirloom.audit;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.repository.EventLogRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3.4 Agent Audit & Monitoring.
 *
 * <p>Aggregates the {@code event_log} to answer three operator questions:
 * <ol>
 *   <li>What has an actor been doing? — {@link #actorActivity}</li>
 *   <li>Is the actor behaving anomalously? — {@link #detectAnomaly}</li>
 *   <li>What did the actor actually do in a session? — {@link #replay}</li>
 * </ol>
 *
 * <p>The Event Log is append-only — these are pure read-side projections.
 */
@Service
public class AuditService {

    /** Denied-rate above this fraction over the window flags the actor. */
    static final double DENIED_RATE_THRESHOLD = 0.25;

    /** Hard minimum of total events before the rate is meaningful (avoid flagging
     *  a single denied event as "100% denial rate"). */
    static final long MIN_EVENTS_FOR_ANOMALY = 10L;

    private final EventLogRepository eventLog;

    public AuditService(EventLogRepository eventLog) {
        this.eventLog = eventLog;
    }

    /** Aggregate event counts for an actor in a time window. */
    public Map<ChangeEvent.EventType, Long> actorActivity(String actor,
                                                          Instant since,
                                                          Instant until) {
        return eventLog.actorEventBreakdown(actor, since, until);
    }

    /**
     * Cheap anomaly check: if the actor's denial rate over the window exceeds
     * {@link #DENIED_RATE_THRESHOLD} AND they have at least
     * {@link #MIN_EVENTS_FOR_ANOMALY} total events, flag them.
     *
     * <p>Production would replace this with EWMA / z-score against a
     * role-baseline; the simple version here is enough to surface the
     * "agent keeps hitting 403s" pattern that the roadmap describes.
     */
    public AnomalyVerdict detectAnomaly(String actor, Instant since, Instant until) {
        long total = eventLog.actorTotalEvents(actor, since, until);
        if (total < MIN_EVENTS_FOR_ANOMALY) {
            return new AnomalyVerdict(false, 0.0, total, 0,
                    "below minimum sample size (" + MIN_EVENTS_FOR_ANOMALY + ")");
        }

        long denied = eventLog.actorEventCount(actor,
                ChangeEvent.EventType.ENTITY_DENIED, since, until);
        double rate = (double) denied / (double) total;

        boolean flagged = rate >= DENIED_RATE_THRESHOLD;
        String reason = flagged
                ? String.format("denied-rate %.0f%% ≥ %.0f%% threshold",
                        rate * 100, DENIED_RATE_THRESHOLD * 100)
                : String.format("denied-rate %.0f%% < %.0f%% threshold",
                        rate * 100, DENIED_RATE_THRESHOLD * 100);
        return new AnomalyVerdict(flagged, rate, total, denied, reason);
    }

    /**
     * Reconstruct an actor's decision chain in chronological order.
     * Returns the raw event list; callers (UI / debug tooling) format it.
     */
    public List<ChangeEvent> replay(String actor, Instant since, Instant until) {
        return eventLog.actorActivity(actor, since, until);
    }

    /** All events touching one entity — for the /history endpoint. */
    public List<ChangeEvent> entityHistoryDirect(String entityFQN, Instant since, Instant until) {
        return eventLog.entityHistory(entityFQN, since, until);
    }

    /** Convenience for "last N hours". */
    public static Instant hoursAgo(int hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    public record AnomalyVerdict(boolean flagged, double deniedRate, long totalEvents,
                                 long deniedEvents, String reason) {}
}