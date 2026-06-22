package com.heirloom.repository;

import com.heirloom.domain.ChangeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventLogJpaRepository extends JpaRepository<ChangeEvent, Long> {

    /** All events for an actor (e.g. {@code agent:007}) in a time window, oldest first. */
    List<ChangeEvent> findByActorAndTimestampBetweenOrderByTimestampAsc(
            String actor, Instant since, Instant until);

    /** All events touching a specific entity, oldest first. */
    List<ChangeEvent> findByEntityFQNAndTimestampBetweenOrderByTimestampAsc(
            String entityFQN, Instant since, Instant until);

    /** Aggregate count of events by type for an actor in a window.
     *  Returns rows of [eventType, count]. */
    @Query("SELECT e.eventType, COUNT(e) FROM ChangeEvent e " +
           "WHERE e.actor = :actor AND e.timestamp BETWEEN :since AND :until " +
           "GROUP BY e.eventType")
    List<Object[]> countByActorGroupedByEventType(@Param("actor") String actor,
                                                  @Param("since") Instant since,
                                                  @Param("until") Instant until);

    /** Total events of any kind for an actor in a window. */
    long countByActorAndTimestampBetween(String actor, Instant since, Instant until);

    /** Denial count specifically — drives anomaly detection. */
    long countByActorAndEventTypeAndTimestampBetween(String actor, ChangeEvent.EventType type,
                                                     Instant since, Instant until);
}