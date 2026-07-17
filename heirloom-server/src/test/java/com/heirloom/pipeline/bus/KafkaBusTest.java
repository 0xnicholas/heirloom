package com.heirloom.pipeline.bus;

import com.heirloom.core.pipeline.*;
import com.heirloom.pipeline.persistence.PipelineRunEntity;
import com.heirloom.pipeline.persistence.PipelineRunJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaBusTest {

    @Mock KafkaTemplate<String, PipelineEvent> kafkaTemplate;
    @Mock PipelineRunJpaRepository runRepo;
    Clock clock = Clock.fixed(Instant.now(), ZoneId.of("UTC"));

    KafkaBus bus;

    @Captor ArgumentCaptor<String> topicCaptor;
    @Captor ArgumentCaptor<String> keyCaptor;
    @Captor ArgumentCaptor<PipelineEvent> eventCaptor;

    @BeforeEach
    void setUp() {
        bus = new KafkaBus(kafkaTemplate, runRepo, clock);
        ReflectionTestUtils.setField(bus, "topic", "heirloom.pipeline.events");
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishSendsEventToKafkaWithPartitionKey() {
        var event = new IngestionRequested(
            java.util.List.of("t1"), UUID.randomUUID(), UUID.randomUUID(),
            "default", "test.db", "corr-1", Instant.now(), 1, "{}");

        CompletableFuture future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), any(PipelineEvent.class)))
            .thenReturn(future);

        bus.publish(event);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), eventCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("heirloom.pipeline.events");
        assertThat(keyCaptor.getValue()).isEqualTo("default::test.db");
        assertThat(eventCaptor.getValue()).isEqualTo(event);
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishFailureMarksRunAsDeadLetter() {
        UUID runUuid = UUID.randomUUID();
        var event = new IngestionRequested(
            java.util.List.of("t1"), UUID.randomUUID(), runUuid,
            "default", "test.db", "corr-1", Instant.now(), 1, "{}");

        CompletableFuture failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(PipelineEvent.class)))
            .thenReturn(failedFuture);

        var run = new PipelineRunEntity();
        run.setRunUuid(runUuid);
        run.setStatus(PipelineStatus.PENDING);
        when(runRepo.findByRunUuid(runUuid)).thenReturn(Optional.of(run));

        bus.publish(event);

        verify(runRepo).save(run);
        assertThat(run.getStatus()).isEqualTo(PipelineStatus.DEAD_LETTER);
    }

    @SuppressWarnings("unchecked")
    @Test
    void partitionKeyFormatIsTenantIdDoubleColonSourceFqn() {
        var event = new IngestionRequested(
            java.util.List.of("t1"), UUID.randomUUID(), UUID.randomUUID(),
            "tenant-42", "source.fqn", "corr-1", Instant.now(), 1, "{}");

        CompletableFuture future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), any(PipelineEvent.class)))
            .thenReturn(future);

        bus.publish(event);

        verify(kafkaTemplate).send(anyString(), keyCaptor.capture(), any());
        assertThat(keyCaptor.getValue()).isEqualTo("tenant-42::source.fqn");
    }

    @Test
    void startDoesNothing() {
        // KafkaAdmin auto-creates topics on startup; no-op is correct
        bus.start();
        verifyNoInteractions(kafkaTemplate, runRepo);
    }
}
