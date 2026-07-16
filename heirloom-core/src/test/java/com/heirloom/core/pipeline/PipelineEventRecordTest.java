package com.heirloom.core.pipeline;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PipelineEventRecordTest {

    @Test
    void ingestionRequestedEnvelopesCommonFields() {
        UUID runUuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        var event = new IngestionRequested(
            List.of("db.public.tbl1", "db.public.tbl2"),
            eventId, runUuid, "default", "db", UUID.randomUUID().toString(),
            now, 1, "{}");

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.runUuid()).isEqualTo(runUuid);
        assertThat(event.tenantId()).isEqualTo("default");
        assertThat(event.sourceFqn()).isEqualTo("db");
        assertThat(event.type()).isEqualTo(PipelineEventType.INGESTION_REQUESTED);
        assertThat(event.tableFqns()).hasSize(2);
    }

    @Test
    void rawDataIngestedCarriesIngestedTableFqns() {
        var event = new RawDataIngested(
            List.of("db.public.tbl1"),
            Instant.now(),
            UUID.randomUUID(), UUID.randomUUID(), "default", "db",
            UUID.randomUUID().toString(), Instant.now(), 1, "{}");

        assertThat(event.type()).isEqualTo(PipelineEventType.RAW_DATA_INGESTED);
        assertThat(event.ingestedTableFqns()).containsExactly("db.public.tbl1");
    }

    @Test
    void semanticAlignedIsTerminalEvent() {
        var event = new SemanticAligned(
            UUID.randomUUID(), UUID.randomUUID(), "default", "db",
            UUID.randomUUID().toString(), Instant.now(), 1, "{}");

        assertThat(event.type()).isEqualTo(PipelineEventType.SEMANTIC_ALIGNED);
    }
}
