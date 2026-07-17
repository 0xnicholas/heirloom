package com.heirloom.repository;

import com.heirloom.domain.ChangeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 4.3: Event Log abstraction.
 * Supports dual transport: direct JDBC (default) and Kafka (for independent deployment).
 */
public interface EventLog {

    /** Append a change event. */
    void append(ChangeEvent event);

    /** All events for an actor in a time window. */
    List<ChangeEvent> actorActivity(String actor, Instant since, Instant until);

    /** All events touching a specific entity. */
    List<ChangeEvent> entityHistory(String entityFQN, Instant since, Instant until);

    /** Event type breakdown for an actor. */
    Map<ChangeEvent.EventType, Long> actorEventBreakdown(String actor,
                                                          Instant since, Instant until);

    /** Total events for an actor. */
    long actorTotalEvents(String actor, Instant since, Instant until);

    /** Count by actor + event type. */
    long actorEventCount(String actor, ChangeEvent.EventType type,
                          Instant since, Instant until);
}
