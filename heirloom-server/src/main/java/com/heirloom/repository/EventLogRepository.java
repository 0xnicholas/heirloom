package com.heirloom.repository;

import com.heirloom.domain.ChangeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "heirloom.event-log.transport", havingValue = "jdbc", matchIfMissing = true)
public class EventLogRepository implements EventLog {
    private final EventLogJpaRepository jpa;

    public EventLogRepository(EventLogJpaRepository jpa) { this.jpa = jpa; }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(ChangeEvent event) { jpa.save(event); }

    @Override
    public List<ChangeEvent> actorActivity(String actor, Instant since, Instant until) {
        return jpa.findByActorAndTimestampBetweenOrderByTimestampAsc(actor, since, until);
    }

    @Override
    public List<ChangeEvent> entityHistory(String entityFQN, Instant since, Instant until) {
        return jpa.findByEntityFQNAndTimestampBetweenOrderByTimestampAsc(entityFQN, since, until);
    }

    @Override
    public Map<ChangeEvent.EventType, Long> actorEventBreakdown(String actor,
                                                                Instant since,
                                                                Instant until) {
        List<Object[]> rows = jpa.countByActorGroupedByEventType(actor, since, until);
        java.util.HashMap<ChangeEvent.EventType, Long> result = new java.util.HashMap<>();
        for (Object[] row : rows) {
            result.put((ChangeEvent.EventType) row[0], (Long) row[1]);
        }
        return result;
    }

    @Override
    public long actorTotalEvents(String actor, Instant since, Instant until) {
        return jpa.countByActorAndTimestampBetween(actor, since, until);
    }

    @Override
    public long actorEventCount(String actor, ChangeEvent.EventType type,
                                Instant since, Instant until) {
        return jpa.countByActorAndEventTypeAndTimestampBetween(actor, type, since, until);
    }
}