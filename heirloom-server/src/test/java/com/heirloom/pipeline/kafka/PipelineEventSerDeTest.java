package com.heirloom.pipeline.kafka;

import com.heirloom.core.pipeline.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PipelineEventSerDeTest {

    private final PipelineEventSerializer serializer = new PipelineEventSerializer();
    private final PipelineEventDeserializer deserializer = new PipelineEventDeserializer();

    @Test
    void roundtripIngestionRequested() {
        var original = new IngestionRequested(
            List.of("db.t1", "db.t2"), UUID.randomUUID(), UUID.randomUUID(),
            "default", "test.db", "corr-1", Instant.now(), 1, "{}");
        assertRoundtrip(original);
    }

    @Test
    void roundtripRawDataIngested() {
        var original = new RawDataIngested(
            List.of("db.t1"), Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
            "default", "test.db", "corr-1", Instant.now(), 1, "{\"count\":5}");
        assertRoundtrip(original);
    }

    @Test
    void roundtripSchemaDiscovered() {
        var original = new SchemaDiscovered(
            List.of("db.t1", "db.t2"), 2, UUID.randomUUID(), UUID.randomUUID(),
            "default", "test.db", "corr-1", Instant.now(), 1, "{}");
        assertRoundtrip(original);
    }

    @Test
    void roundtripDataProfiled() {
        var original = new DataProfiled(
            List.of("db.t1"), 1, 0.85, UUID.randomUUID(), UUID.randomUUID(),
            "default", "test.db", "corr-1", Instant.now(), 1, "{\"score\":0.85}");
        assertRoundtrip(original);
    }

    @Test
    void roundtripSemanticAligned() {
        var original = new SemanticAligned(
            UUID.randomUUID(), UUID.randomUUID(),
            "default", "test.db", "corr-1", Instant.now(), 1, "{}");
        assertRoundtrip(original);
    }

    @Test
    void nullDataReturnsNull() {
        assertThat(serializer.serialize("topic", null)).isNull();
        assertThat(deserializer.deserialize("topic", null)).isNull();
    }

    private void assertRoundtrip(PipelineEvent original) {
        byte[] bytes = serializer.serialize("test-topic", original);
        assertThat(bytes).isNotNull();

        PipelineEvent deserialized = deserializer.deserialize("test-topic", bytes);
        assertThat(deserialized).isNotNull();

        // 验证 type 正确
        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.eventId()).isEqualTo(original.eventId());
        assertThat(deserialized.runUuid()).isEqualTo(original.runUuid());
        assertThat(deserialized.tenantId()).isEqualTo(original.tenantId());
        assertThat(deserialized.sourceFqn()).isEqualTo(original.sourceFqn());
        assertThat(deserialized.correlationId()).isEqualTo(original.correlationId());
        assertThat(deserialized.payloadVersion()).isEqualTo(original.payloadVersion());
        assertThat(deserialized.payload()).isEqualTo(original.payload());
    }
}
