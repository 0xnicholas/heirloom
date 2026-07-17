package com.heirloom.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.heirloom.domain.ChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Phase 4.3: Kafka-backed Event Log.
 * Active when {@code heirloom.event-log.transport=kafka}.
 * <p>
 * Append path: publishes ChangeEvent to {@code heirloom.event.log} topic.
 * Query path: maintained by {@link EventLogProjector} (Kafka consumer → Postgres).
 */
@Component
@ConditionalOnProperty(name = "heirloom.event-log.transport", havingValue = "kafka")
public class KafkaEventLog implements EventLog {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventLogRepository fallback;

    public KafkaEventLog(KafkaTemplate<String, String> kafkaTemplate,
                          EventLogRepository fallback) {
        this.kafkaTemplate = kafkaTemplate;
        this.fallback = fallback;
    }

    @Override
    public void append(ChangeEvent event) {
        try {
            String json = MAPPER.writeValueAsString(event);
            String key = event.getEntityFQN() != null
                ? event.getEntityFQN()
                : "event." + System.currentTimeMillis();
            kafkaTemplate.send("heirloom.event.log", key, json);
            log.debug("Event published to Kafka: {} {} {}", event.getEventType(), event.getActor(), key);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: {}", e.getMessage());
        }
    }

    // Query methods delegate to Projector-populated PostgreSQL (fallback repo).
    // The Projector reads from heirloom.event.log topic and writes to event_log table.

    @Override
    public List<ChangeEvent> actorActivity(String actor, Instant since, Instant until) {
        return fallback.actorActivity(actor, since, until);
    }

    @Override
    public List<ChangeEvent> entityHistory(String entityFQN, Instant since, Instant until) {
        return fallback.entityHistory(entityFQN, since, until);
    }

    @Override
    public Map<ChangeEvent.EventType, Long> actorEventBreakdown(String actor,
                                                                  Instant since, Instant until) {
        return fallback.actorEventBreakdown(actor, since, until);
    }

    @Override
    public long actorTotalEvents(String actor, Instant since, Instant until) {
        return fallback.actorTotalEvents(actor, since, until);
    }

    @Override
    public long actorEventCount(String actor, ChangeEvent.EventType type,
                                 Instant since, Instant until) {
        return fallback.actorEventCount(actor, type, since, until);
    }
}
