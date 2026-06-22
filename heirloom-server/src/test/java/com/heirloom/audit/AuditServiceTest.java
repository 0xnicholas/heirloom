package com.heirloom.audit;

import com.heirloom.domain.ChangeEvent;
import com.heirloom.repository.EventLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private final EventLogRepository eventLog = mock(EventLogRepository.class);
    private final AuditService service = new AuditService(eventLog);

    private static final Instant NOW = Instant.parse("2026-06-22T12:00:00Z");

    @Test
    void anomaly_flagsHighDenialRate() {
        when(eventLog.actorTotalEvents("agent:007", NOW, NOW)).thenReturn(20L);
        when(eventLog.actorEventCount(eq("agent:007"), eq(ChangeEvent.EventType.ENTITY_DENIED),
                any(), any())).thenReturn(8L);

        AuditService.AnomalyVerdict v = service.detectAnomaly("agent:007", NOW, NOW);

        assertThat(v.flagged()).isTrue();
        assertThat(v.deniedRate()).isEqualTo(0.4);
        assertThat(v.totalEvents()).isEqualTo(20L);
        assertThat(v.deniedEvents()).isEqualTo(8L);
        assertThat(v.reason()).contains("40%").contains("25%");
    }

    @Test
    void anomaly_doesNotFlagLowDenialRate() {
        when(eventLog.actorTotalEvents("agent:007", NOW, NOW)).thenReturn(50L);
        when(eventLog.actorEventCount(eq("agent:007"), eq(ChangeEvent.EventType.ENTITY_DENIED),
                any(), any())).thenReturn(2L);

        AuditService.AnomalyVerdict v = service.detectAnomaly("agent:007", NOW, NOW);

        assertThat(v.flagged()).isFalse();
        assertThat(v.deniedRate()).isEqualTo(0.04);
    }

    @Test
    void anomaly_skipsSmallSample() {
        // A single denied event would be 100% denial rate — but the actor has
        // only made 3 requests so far, statistically meaningless.
        when(eventLog.actorTotalEvents("agent:new", NOW, NOW)).thenReturn(3L);

        AuditService.AnomalyVerdict v = service.detectAnomaly("agent:new", NOW, NOW);

        assertThat(v.flagged()).isFalse();
        assertThat(v.reason()).contains("minimum sample size");
    }

    @Test
    void anomaly_thresholdIsInclusiveAtBoundary() {
        // 25% denied — equal to threshold, flag (we use >= so the boundary
        // is treated as "alert-worthy" not "safe").
        when(eventLog.actorTotalEvents("agent:007", NOW, NOW)).thenReturn(20L);
        when(eventLog.actorEventCount(eq("agent:007"), eq(ChangeEvent.EventType.ENTITY_DENIED),
                any(), any())).thenReturn(5L);

        AuditService.AnomalyVerdict v = service.detectAnomaly("agent:007", NOW, NOW);

        assertThat(v.deniedRate()).isEqualTo(0.25);
        assertThat(v.flagged()).isTrue();
    }

    @Test
    void replay_returnsEventsInOrder() throws Exception {
        ChangeEvent a = withTimestamp(new ChangeEvent(), NOW);
        ChangeEvent b = withTimestamp(new ChangeEvent(), NOW.plusSeconds(1));
        when(eventLog.actorActivity("agent:007", NOW, NOW)).thenReturn(List.of(a, b));

        List<ChangeEvent> events = service.replay("agent:007", NOW, NOW);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getTimestamp()).isEqualTo(NOW);
        assertThat(events.get(1).getTimestamp()).isEqualTo(NOW.plusSeconds(1));
    }

    /** ChangeEvent has no setTimestamp(); use reflection for tests. */
    private static ChangeEvent withTimestamp(ChangeEvent e, Instant t) throws Exception {
        var field = ChangeEvent.class.getDeclaredField("timestamp");
        field.setAccessible(true);
        field.set(e, t);
        return e;
    }

    @Test
    void activity_returnsFullBreakdownMap() {
        when(eventLog.actorEventBreakdown("agent:007", NOW, NOW))
                .thenReturn(Map.of(ChangeEvent.EventType.ENTITY_CREATED, 5L,
                                   ChangeEvent.EventType.FUNCTION_INVOKED, 12L));

        Map<ChangeEvent.EventType, Long> result = service.actorActivity("agent:007", NOW, NOW);

        assertThat(result.get(ChangeEvent.EventType.ENTITY_CREATED)).isEqualTo(5L);
        assertThat(result.get(ChangeEvent.EventType.FUNCTION_INVOKED)).isEqualTo(12L);
    }

    @Test
    void hoursAgo_helper() {
        Instant t = AuditService.hoursAgo(6);
        long deltaMs = Instant.now().toEpochMilli() - t.toEpochMilli();
        // Allow ±1 min slack for clock drift / test execution time.
        assertThat(deltaMs).isBetween(6 * 3_600_000L - 60_000L, 6 * 3_600_000L + 60_000L);
    }
}