package com.heirloom.pipeline.bus;

import com.heirloom.core.pipeline.PipelineEvent;
import com.heirloom.core.pipeline.PipelineEventBus;
import com.heirloom.core.pipeline.PipelineStatus;
import com.heirloom.pipeline.persistence.PipelineRunJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class KafkaBus implements PipelineEventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaBus.class);

    private final KafkaTemplate<String, PipelineEvent> kafkaTemplate;
    private final PipelineRunJpaRepository runRepo;
    private final Clock clock;

    @Value("${heirloom.pipeline.kafka.topic-events}")
    private String topic;

    public KafkaBus(KafkaTemplate<String, PipelineEvent> kafkaTemplate,
                     PipelineRunJpaRepository runRepo,
                     Clock clock) {
        this.kafkaTemplate = kafkaTemplate;
        this.runRepo = runRepo;
        this.clock = clock;
    }

    @Override
    public void publish(PipelineEvent event) {
        String partitionKey = event.tenantId() + "::" + event.sourceFqn();
        kafkaTemplate.send(topic, partitionKey, event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka publish failed for eventId={}: {}",
                        event.eventId(), ex.getMessage(), ex);
                    markRunAsProducerError(event.runUuid(), ex.getMessage());
                }
            });
    }

    @Override
    public void start() {
        // Kafka 启动无需特殊动作；KafkaAdmin 在启动时自动建 topic
    }

    private void markRunAsProducerError(java.util.UUID runUuid, String error) {
        runRepo.findByRunUuid(runUuid).ifPresent(run -> {
            run.setStatus(PipelineStatus.DEAD_LETTER);
            run.setUpdatedAt(clock.instant());
            run.setCompletedAt(clock.instant());
            runRepo.save(run);
            log.warn("Run {} marked DEAD_LETTER due to producer error: {}", runUuid, error);
        });
    }
}
