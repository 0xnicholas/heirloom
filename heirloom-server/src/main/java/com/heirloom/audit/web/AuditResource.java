package com.heirloom.audit.web;

import com.heirloom.audit.AuditService;
import com.heirloom.audit.AuditService.AnomalyVerdict;
import com.heirloom.domain.ChangeEvent;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3.4 Agent Audit & Monitoring endpoints. All read-only.
 *
 * <ul>
 *   <li>{@code GET /v1/audit/actors/{actor}/activity?since=&until=}
 *       — event counts grouped by type (dashboard widget)</li>
 *   <li>{@code GET /v1/audit/actors/{actor}/anomaly?since=&until=}
 *       — denial-rate verdict (alerting hook)</li>
 *   <li>{@code GET /v1/audit/actors/{actor}/replay?since=&until=}
 *       — chronological event list (operator debugging)</li>
 *   <li>{@code GET /v1/audit/entities/{fqn}/history?since=&until=}
 *       — all events touching one entity (forensics)</li>
 * </ul>
 *
 * <p>{@code until} defaults to now; {@code since} defaults to 24h ago.
 * Both accept ISO-8601 timestamps ({@code 2026-06-22T00:00:00Z}).
 */
@RestController
@RequestMapping("/v1/audit")
public class AuditResource {

    private final AuditService audit;

    public AuditResource(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/actors/{actor}/activity")
    public ResponseEntity<Map<String, Object>> actorActivity(
            @PathVariable String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {

        Instant sinceTs = defaultSince(since);
        Instant untilTs = defaultUntil(until);

        Map<ChangeEvent.EventType, Long> breakdown = audit.actorActivity(actor, sinceTs, untilTs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor);
        body.put("since", sinceTs);
        body.put("until", untilTs);
        Map<String, Long> serialised = new LinkedHashMap<>();
        // Iterate the enum so callers see a stable shape even when zero.
        for (ChangeEvent.EventType t : ChangeEvent.EventType.values()) {
            serialised.put(t.name(), breakdown.getOrDefault(t, 0L));
        }
        body.put("eventCounts", serialised);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/actors/{actor}/anomaly")
    public ResponseEntity<AnomalyVerdict> anomaly(
            @PathVariable String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {
        return ResponseEntity.ok(audit.detectAnomaly(actor, defaultSince(since), defaultUntil(until)));
    }

    @GetMapping("/actors/{actor}/replay")
    public ResponseEntity<Map<String, Object>> replay(
            @PathVariable String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {

        Instant sinceTs = defaultSince(since);
        Instant untilTs = defaultUntil(until);
        List<ChangeEvent> events = audit.replay(actor, sinceTs, untilTs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actor", actor);
        body.put("since", sinceTs);
        body.put("until", untilTs);
        body.put("eventCount", events.size());
        body.put("events", events);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/entities/{fqn}/history")
    public ResponseEntity<Map<String, Object>> entityHistory(
            @PathVariable String fqn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {
        // Use the actorActivity method with the entity as the actor would be wrong;
        // entity history lives directly in EventLogRepository.
        Instant sinceTs = defaultSince(since);
        Instant untilTs = defaultUntil(until);
        // Service-layer would be cleaner; keep it inline for the MVP.
        var events = audit.entityHistoryDirect(fqn, sinceTs, untilTs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entityFQN", fqn);
        body.put("since", sinceTs);
        body.put("until", untilTs);
        body.put("eventCount", events.size());
        body.put("events", events);
        return ResponseEntity.ok(body);
    }

    private static Instant defaultSince(Instant since) {
        return since != null ? since : AuditService.hoursAgo(24);
    }

    private static Instant defaultUntil(Instant until) {
        return until != null ? until : Instant.now();
    }
}