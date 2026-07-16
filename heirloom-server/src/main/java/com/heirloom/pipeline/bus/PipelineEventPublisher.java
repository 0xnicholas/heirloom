package com.heirloom.pipeline.bus;

import com.heirloom.core.pipeline.IngestionRequested;
import com.heirloom.core.pipeline.PipelineEvent;
import com.heirloom.core.pipeline.PipelineEventBus;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class PipelineEventPublisher {

    private final PipelineEventBus bus;

    public PipelineEventPublisher(PipelineEventBus bus) {
        this.bus = bus;
    }

    public void publishIngestionRequested(UUID runUuid, String tenantId, String sourceFqn,
                                           List<String> tableFqns,
                                           String correlationId) {
        PipelineEvent event = new IngestionRequested(
            tableFqns, UUID.randomUUID(), runUuid, tenantId, sourceFqn,
            correlationId, Instant.now(), 1, "{}");
        bus.publish(event);
    }
}