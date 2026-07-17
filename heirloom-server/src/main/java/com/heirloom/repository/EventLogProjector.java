package com.heirloom.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.heirloom.domain.ChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Phase 4.3: Event Log Projector.
 * Consumes ChangeEvent records from heirloom.event.log topic and persists
 * them to PostgreSQL. Enables the Kafka transport while keeping query
 * performance via the existing event_log table.
 */
@Component
@ConditionalOnProperty(name = "heirloom.event-log.transport", havingValue = "kafka")
public class EventLogProjector {

    private static final Logger log = LoggerFactory.getLogger(EventLogProjector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final EventLogJpaRepository jpa;

    public EventLogProjector(EventLogJpaRepository jpa) {
        this.jpa = jpa;
    }

    @KafkaListener(
        topics = "heirloom.event.log",
        groupId = "heirloom-event-log-projector"
    )
    public void onEvent(String message) {
        try {
            ChangeEvent event = MAPPER.readValue(message, ChangeEvent.class);
            jpa.save(event);
            log.debug("Event log projected: {} {}",
                event.getEventType(), event.getEntityFQN());
        } catch (Exception e) {
            log.error("Failed to project event: {}", e.getMessage());
        }
    }
}
