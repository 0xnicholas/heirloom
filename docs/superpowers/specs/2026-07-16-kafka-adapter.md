# Heirloom Kafka Adapter — 设计规格

**日期**: 2026-07-17
**状态**: Draft
**参考**: ADR-038、ADR-039、Phase 7a spec
**范围**: Phase 7b — Kafka adapter 实现 PipelineEventBus，替换 Phase 7a 的 InProcessBus + DB outbox

---

## 1. 背景

Phase 7a 实现了基于 DB outbox + 内存调度的 in-process 管线骨架（4 阶段：Ingestion → Discovery → Profiling → Alignment），单进程部署可行。但 ADR-038 / ADR-039 早已规划 Kafka 作为正式事件总线，DB outbox 是无 Kafka 时的脚手架。

Phase 7b 落地 Kafka adapter：
- 用 Kafka producer/consumer 实现 `PipelineEventBus` 接口
- 删除 Phase 7a 的 InProcessBus / OutboxProcessor / `pipeline_outbox` 表
- 保留 `pipeline_runs` / `pipeline_run_stages` / `pipeline_run_results` / `pipeline_dead_letter` / `pipeline_stage_executions`（查询与重试状态模型，与传输无关）
- 引入 Projector 消费事件维护查询模型（运行级状态、stage 进度）
- 4 个 stage consumer（每阶段一个 consumer group）；Phase 7b 只 IngestionListener 真实实现，其余 3 个是 stub

### 1.1 与 Phase 7a 的差异

| 主题 | Phase 7a | Phase 7b |
|---|---|---|
| 事件传输 | DB outbox + @Scheduled poll | Kafka topic |
| 持久化 | pipeline_outbox 表 + run/stage/result 表 | 仅 run/stage/result/dlq/stage_executions（删 outbox） |
| 多实例扩展 | 仅靠 `claimed_until` lease | Kafka partition 天然支持水平扩展 |
| 崩溃恢复 | OutboxProcessor poll + lease 回收 | Kafka consumer offset 重读 + stage idempotency 表 |
| 重试 | DB-driven 退避（next_retry_at 列） | Kafka consumer 内置重试 + 简化退避 |
| DLQ | pipeline_dead_letter 表 | 表保留 + 新增 `heirloom.pipeline.dlq` topic（双写） |
| 启动恢复 | InProcessBus.start() 释放过期 claim | KafkaAdmin 自动建 topic；consumer offset 自动重平衡 |
| Producer 接口契约 | `bus.publish()` 写 DB + 更新 run status | `bus.publish()` 只发 Kafka；run status 由 Projector 维护 |

### 1.2 与 ADR-038 / ADR-039 的对齐

| ADR 决策 | Phase 7b 实现 |
|---|---|
| 单 topic `heirloom.pipeline.events` | ✅ KafkaTopicConfig 建该 topic |
| 分区键 `tenantId + sourceId` 哈希 | ✅ `KafkaBus` 用 `tenantId::sourceFqn` 作 partition key |
| 每阶段独立 consumer group | ✅ 5 个 group（1 projector + 4 stage） |
| CloudEvents 风格 envelope | ✅ PipelineEvent 已有 envelope（eventId, type, payloadVersion, tenantId, sourceFqn, correlationId） |
| payloadVersion schema 演进 | ✅ 每条事件带 payloadVersion 字段 |
| DLQ topic `heirloom.pipeline.dlq` | ✅ KafkaTopicConfig 建 DLQ topic；stage fatal → 双写 DLQ 表 + topic |

---

## 2. 设计目标

### 2.1 Phase 7b 必达

- KafkaBus 实现 `PipelineEventBus` 接口（替换 InProcessBus）
- KafkaTopicConfig 自动创建 `heirloom.pipeline.events` + `heirloom.pipeline.dlq`
- PipelineEventSerializer / Deserializer 支持 5 种事件类型
- PipelineEventProjector `@KafkaListener` 维护 `pipeline_runs` / `pipeline_run_stages` 状态
- StageConsumerTemplate 抽象基类：共享重试 / DLQ / 幂等逻辑
- 4 个 stage listener，**只 IngestionListener 真实实现**；Discovery/Profiling/Alignment 是 stub（Phase 7c 接）
- 删除 Phase 7a 的 InProcessBus / PipelineEventPublisher / OutboxProcessor / PipelineOutboxEntity / PipelineOutboxJpaRepository / InProcessBusTest / V25 迁移文件
- V28 删 `pipeline_outbox` 表
- @EmbeddedKafka 单元 + 集成测试覆盖关键路径
- 端到端：POST /v1/pipeline/runs → Kafka topic → IngestionListener → RawDataIngested → stub listeners → run COMPLETED

### 2.2 非目标（Phase 7b 不做）

- 阶段 5-8（Entity Resolution / Ontology Proposal / Governance / Mapping & Publish）
- 多租户隔离（ADR-042，字段已预留）
- 真正的延迟重试（Kafka Streams / Spring Cloud Stream）—— Phase 7b 简化为 Kafka 内置重试 + 立即重发
- 调度器触发、CDC 事件触发、DLQ 重放 UI
- Kafka 事务（exactly-once）—— Phase 7b 接受 at-least-once，靠 stage 幂等保证
- 替换 stub listeners 为真实实现 —— 留 Phase 7c
- producer 发送失败的 run 状态补救 —— Phase 7c 再考虑

---

## 3. 架构与模块布局

### 3.1 接口模块（heirloom-core，零变更）

Phase 7a 已有的接口全部保留：`PipelineEventBus` / `PipelineEvent` / `PipelineEventType` / `PipelineStage` / `PipelineStageRegistry` / `PipelineContext` / `PipelineRun` / `PipelineStageStatus` / `PipelineStatus` / `PipelineTriggerType` / `PipelineFailure` / `RecoverableFailure` / `FatalFailure`。

**PipelineStageRegistry / DefaultPipelineStageRegistry / PipelineOrchestrator 删除决策：** Phase 7b 的 stage listener 直接委派给 stage bean（`stage.apply()`），不需要 registry 做事件 → stage 路由。`PipelineStageRegistry` 接口、`DefaultPipelineStageRegistry` 实现、`PipelineOrchestrator` 装配类全部删除。`PipelineStage` 接口保留（stage bean 实现它，但无注册表）。

### 3.2 实现模块（heirloom-server）

```
heirloom-server/src/main/java/com/heirloom/pipeline/
├── bus/
│   └── KafkaBus.java                       # PipelineEventBus impl (NEW, 替换 InProcessBus)
├── kafka/
│   ├── KafkaTopics.java                    # 常量：topic 名称、group id (NEW)
│   ├── KafkaTopicConfig.java               # KafkaAdmin NewTopic beans (NEW)
│   ├── KafkaProducerConfig.java            # ProducerFactory + KafkaTemplate (NEW)
│   ├── KafkaConsumerConfig.java            # ConcurrentKafkaListenerContainerFactory (NEW)
│   ├── PipelineEventSerializer.java        # implements Serializer<PipelineEvent> (NEW)
│   └── PipelineEventDeserializer.java      # implements Deserializer<PipelineEvent> (NEW)
├── projector/
│   └── PipelineEventProjector.java         # @KafkaListener 维护 DB 状态 (NEW)
├── consumer/
│   ├── StageConsumerTemplate.java          # 抽象基类 (NEW)
│   ├── PipelineIngestionListener.java      # 真实实现 (NEW, 委派 7a 的 PipelineIngestionStage bean)
│   ├── PipelineDiscoveryListener.java      # stub for Phase 7c (NEW)
│   ├── PipelineProfilingListener.java      # stub for Phase 7c (NEW)
│   └── PipelineAlignmentListener.java      # stub for Phase 7c (NEW)
├── stages/                                  # 保留 7a 的 4 个 @Component stage beans（无 orchestrator）
│   ├── PipelineIngestionStage.java         # 现有
│   ├── PipelineDiscoveryStage.java         # 现有
│   ├── PipelineProfilingStage.java         # 现有
│   └── PipelineAlignmentStage.java         # 现有
├── service/
│   └── PipelineService.java                # 改：startRun 调 bus.publish（不写 DB run/status）
├── persistence/                              # 保留，删 PipelineOutbox*
│   ├── PipelineRunEntity.java
│   ├── PipelineStageStatusEntity.java
│   ├── PipelineResultEntity.java
│   ├── DeadLetterEntity.java
│   ├── PipelineStageExecutionEntity.java
│   └── (各 JpaRepository — 删 PipelineOutboxJpaRepository)
└── web/
    └── PipelineResource.java                # 现有，不变
```

### 3.3 删除清单（Phase 7b）

```
删除文件:
- heirloom-server/src/main/java/com/heirloom/pipeline/bus/InProcessBus.java
- heirloom-server/src/main/java/com/heirloom/pipeline/bus/PipelineEventPublisher.java
- heirloom-server/src/main/java/com/heirloom/pipeline/processor/OutboxProcessor.java
- heirloom-server/src/main/java/com/heirloom/pipeline/stages/PipelineOrchestrator.java
- heirloom-server/src/main/java/com/heirloom/pipeline/stages/DefaultPipelineStageRegistry.java
- heirloom-server/src/main/java/com/heirloom/pipeline/persistence/PipelineOutboxEntity.java
- heirloom-server/src/main/java/com/heirloom/pipeline/persistence/PipelineOutboxJpaRepository.java
- heirloom-server/src/test/java/com/heirloom/pipeline/bus/InProcessBusTest.java
- heirloom-server/src/main/resources/db/migration/V25__create_pipeline_outbox.sql
```

新建迁移:
- heirloom-server/src/main/resources/db/migration/V28__drop_pipeline_outbox.sql

注：`PipelineStageRegistry` 接口（heirloom-core）也删除——Phase 7b 不再有事件 → stage 注册概念。

### 3.4 heairloom-core 接口清理

`PipelineStageRegistry.java` 接口文件删除。`PipelineStage` 接口保留（stage bean 实现）。其他接口无变化。

---

## 4. Kafka 配置（application.yml）

```yaml
heirloom:
  pipeline:
    kafka:
      bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
      topic-events: heirloom.pipeline.events
      topic-dlq: heirloom.pipeline.dlq
      partitions: 3
      replication-factor: 1
      consumer:
        auto-offset-reset: earliest
        enable-auto-commit: false
      producer:
        acks: all
        enable-idempotence: true
        retries: 5
        delivery-timeout-ms: 30000

spring:
  kafka:
    bootstrap-servers: ${heirloom.pipeline.kafka.bootstrap-servers}
    producer:
      acks: ${heirloom.pipeline.kafka.producer.acks}
      properties:
        enable.idempotence: ${heirloom.pipeline.kafka.producer.enable-idempotence}
        delivery.timeout.ms: ${heirloom.pipeline.kafka.producer.delivery-timeout-ms}
        retries: ${heirloom.pipeline.kafka.producer.retries}
    consumer:
      auto-offset-reset: ${heirloom.pipeline.kafka.consumer.auto-offset-reset}
      enable-auto-commit: ${heirloom.pipeline.kafka.consumer.enable-auto-commit}
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: com.heirloom.pipeline.kafka.PipelineEventDeserializer
```

### 4.1 KafkaTopicConfig

```java
@Configuration
public class KafkaTopicConfig {

    @Value("${heirloom.pipeline.kafka.topic-events}")
    private String topicEvents;

    @Value("${heirloom.pipeline.kafka.topic-dlq}")
    private String topicDlq;

    @Value("${heirloom.pipeline.kafka.partitions:3}")
    private int partitions;

    @Value("${heirloom.pipeline.kafka.replication-factor:1}")
    private short replicationFactor;

    @Bean
    public KafkaAdmin.NewTopic eventsTopic() {
        return TopicBuilder.name(topicEvents)
            .partitions(partitions)
            .replicas(replicationFactor)
            .config("retention.ms", "604800000")  // 7 days
            .build();
    }

    @Bean
    public KafkaAdmin.NewTopic dlqTopic() {
        return TopicBuilder.name(topicDlq)
            .partitions(partitions)
            .replicas(replicationFactor)
            .config("retention.ms", "2592000000")  // 30 days
            .build();
    }
}
```

---

## 5. KafkaBus（PipelineEventBus 实现）

```java
@Component
public class KafkaBus implements PipelineEventBus {

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
                    // Producer 发送失败（broker 不可达 / 序列化错误 / 超时）：
                    // 标记 run 为 DEAD_LETTER 让 GET /v1/pipeline/runs 可见
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

    private void markRunAsProducerError(UUID runUuid, String error) {
        runRepo.findByRunUuid(runUuid).ifPresent(run -> {
            run.setStatus(PipelineStatus.DEAD_LETTER);
            run.setUpdatedAt(clock.instant());
            run.setCompletedAt(clock.instant());
            // 复用 last_error 列（PipelineRunEntity 暂无 last_error 字段——
            // 若 spec 已添加则填，否则只更新状态）
            runRepo.save(run);
            log.warn("Run {} marked DEAD_LETTER due to producer error: {}", runUuid, error);
        });
    }
}
```

**关键约束：**
- `publish()` 只发 Kafka，**不写 DB**。`pipeline_runs` 状态由 Projector 消费 `IngestionRequested` 后维护。
- 同步发送（`kafkaTemplate.send()` 返回 `CompletableFuture`），但 `whenComplete` 回调处理失败：不抛异常给 caller（caller 是 PipelineService.startRun），而是后台异步更新 run 状态。
- `acks=all` + `enable.idempotence=true` + `retries=5` 保证 broker 收到后才算成功（producer 端 at-least-once）。
- partition key 保证同一 `(tenantId, sourceFqn)` 的事件进入同一分区，顺序保留。

**Producer 失败的可见性（C4 修复）：**
- 失败 → `pipeline_runs.status = DEAD_LETTER` + 可选 `last_error`
- GET /v1/pipeline/runs/{uuid} 立即可见失败，无需轮询
- 避免"永远 PENDING"的黑洞

---

## 6. 数据模型

### 6.1 V28 — 删 `pipeline_outbox` 表

```sql
-- V28__drop_pipeline_outbox.sql
DROP TABLE IF EXISTS pipeline_outbox CASCADE;
```

### 6.2 保留表（无 schema 变更）

- `pipeline_runs` — 运行级状态
- `pipeline_run_stages` — 阶段级状态、attempts、next_retry_at
- `pipeline_run_results` — 阶段结果持久化
- `pipeline_dead_letter` — DLQ（表）
- `pipeline_stage_executions` — 幂等键（input_event_id, stage_name）→ COMPLETED

---

## 7. PipelineEvent 序列化

### 7.1 PipelineEventDeserializer

```java
public class PipelineEventDeserializer implements Deserializer<PipelineEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Override
    public PipelineEvent deserialize(String topic, byte[] data) {
        if (data == null) return null;
        try {
            JsonNode root = MAPPER.readTree(data);
            String type = root.get("type").asText();
            return switch (PipelineEventType.valueOf(type)) {
                case INGESTION_REQUESTED -> MAPPER.treeToValue(root, IngestionRequested.class);
                case RAW_DATA_INGESTED -> MAPPER.treeToValue(root, RawDataIngested.class);
                case SCHEMA_DISCOVERED -> MAPPER.treeToValue(root, SchemaDiscovered.class);
                case DATA_PROFILED -> MAPPER.treeToValue(root, DataProfiled.class);
                case SEMANTIC_ALIGNED -> MAPPER.treeToValue(root, SemanticAligned.class);
            };
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize PipelineEvent", e);
        }
    }
}
```

**关键：** 用 `JsonNode` 先读 `type` 字段再分派到具体 record —— 不用 `@JsonTypeInfo` 注解（污染 record）。

### 7.2 PipelineEventSerializer

```java
public class PipelineEventSerializer implements Serializer<PipelineEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Override
    public byte[] serialize(String topic, PipelineEvent event) {
        if (event == null) return null;
        try {
            return MAPPER.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize PipelineEvent", e);
        }
    }
}
```

注：record 序列化时 Jackson 用 component names 作为 JSON field names（`eventId`, `runUuid`, `type` 等），与 deserializer 期望一致。

---

## 8. Consumer Group 命名

| Component | group.id |
|---|---|
| PipelineEventProjector | `heirloom-pipeline-projector` |
| PipelineIngestionListener | `heirloom-pipeline-stage-ingestion` |
| PipelineDiscoveryListener (stub) | `heirloom-pipeline-stage-discovery` |
| PipelineProfilingListener (stub) | `heirloom-pipeline-stage-profiling` |
| PipelineAlignmentListener (stub) | `heirloom-pipeline-stage-alignment` |

每个 group 独立消费同一 topic，互不阻塞。

---

## 9. PipelineEventProjector

```java
@Component
public class PipelineEventProjector {

    private static final Logger log = LoggerFactory.getLogger(PipelineEventProjector.class);

    private final PipelineRunJpaRepository runRepo;
    private final PipelineStageStatusJpaRepository stageRepo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final KafkaTemplate<String, PipelineEvent> kafkaTemplate;

    @Value("${heirloom.pipeline.kafka.topic-dlq}")
    private String dlqTopic;

    public PipelineEventProjector(PipelineRunJpaRepository runRepo,
                                   PipelineStageStatusJpaRepository stageRepo,
                                   ObjectMapper mapper,
                                   Clock clock,
                                   KafkaTemplate<String, PipelineEvent> kafkaTemplate) {
        this.runRepo = runRepo;
        this.stageRepo = stageRepo;
        this.mapper = mapper;
        this.clock = clock;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
        topics = "${heirloom.pipeline.kafka.topic-events}",
        groupId = "heirloom-pipeline-projector"
    )
    public void onEvent(PipelineEvent event) {
        try {
            switch (event.type()) {
                case INGESTION_REQUESTED -> onIngestionRequested(event);
                case RAW_DATA_INGESTED, SCHEMA_DISCOVERED, DATA_PROFILED, SEMANTIC_ALIGNED
                    -> onStageEvent(event);
            }
        } catch (Exception e) {
            log.error("Projector failed for eventId={}: {}", event.eventId(), e.getMessage(), e);
            // 不抛：让 Kafka commit offset，避免单条事件卡死整个 partition
            sendToDlq(event, "projector: " + e.getMessage());
        }
    }

    private void onIngestionRequested(PipelineEvent event) {
        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow(
            () -> new IllegalStateException("PipelineRun not found: " + event.runUuid()));
        run.setStatus(PipelineStatus.RUNNING);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);
        ensureStage(event.runUuid(), "ingestion");
    }

    private void onStageEvent(PipelineEvent event) {
        // Projector 不"完成" stage —— stage consumer 自己做
        // 此处只确保对应 stage 行存在（首次到达时）
        String stageName = inferStageName(event.type());
        ensureStage(event.runUuid(), stageName);
    }

    private void ensureStage(UUID runUuid, String stageName) {
        if (stageRepo.findByRunUuidAndStageName(runUuid, stageName).isEmpty()) {
            var s = new PipelineStageStatusEntity();
            s.setRunUuid(runUuid);
            s.setStageName(stageName);
            s.setStatus(PipelineStatus.PENDING);
            s.setAttempts(0);
            s.setMaxAttempts(3);
            stageRepo.save(s);
        }
    }

    private String inferStageName(PipelineEventType type) {
        return switch (type) {
            case RAW_DATA_INGESTED -> "discovery";
            case SCHEMA_DISCOVERED -> "profiling";
            case DATA_PROFILED -> "alignment";
            case SEMANTIC_ALIGNED -> "alignment";
            default -> "unknown";
        };
    }

    private void sendToDlq(PipelineEvent event, String reason) {
        kafkaTemplate.send(dlqTopic, event.tenantId() + "::" + event.sourceFqn(), event);
        log.warn("Event {} sent to DLQ: {}", event.eventId(), reason);
    }
}
```

**Projector 职责极简**：只反映事件流到查询模型，不做业务逻辑。stage 完成由 stage consumer 维护。

---

## 10. StageConsumerTemplate（共享消费骨架）

```java
public abstract class StageConsumerTemplate {

    private static final Logger log = LoggerFactory.getLogger(StageConsumerTemplate.class);

    /** Phase 7b 全局固定 4 个 stage 名。"allStagesComplete" 据此判断而非 "现存 row 全 COMPLETED"。 */
    public static final java.util.List<String> ALL_STAGE_NAMES =
        java.util.List.of("ingestion", "discovery", "profiling", "alignment");

    private final PipelineRunJpaRepository runRepo;
    private final PipelineStageStatusJpaRepository stageRepo;
    private final PipelineStageExecutionJpaRepository execRepo;
    private final DeadLetterJpaRepository dlqRepo;
    private final PipelineResultJpaRepository resultRepo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final KafkaTemplate<String, PipelineEvent> kafkaTemplate;

    @Value("${heirloom.pipeline.kafka.topic-events}")
    private String eventsTopic;

    @Value("${heirloom.pipeline.kafka.topic-dlq}")
    private String dlqTopic;

    protected StageConsumerTemplate(PipelineRunJpaRepository runRepo,
                                      PipelineStageStatusJpaRepository stageRepo,
                                      PipelineStageExecutionJpaRepository execRepo,
                                      DeadLetterJpaRepository dlqRepo,
                                      PipelineResultJpaRepository resultRepo,
                                      ObjectMapper mapper,
                                      Clock clock,
                                      KafkaTemplate<String, PipelineEvent> kafkaTemplate) {
        this.runRepo = runRepo;
        this.stageRepo = stageRepo;
        this.execRepo = execRepo;
        this.dlqRepo = dlqRepo;
        this.resultRepo = resultRepo;
        this.mapper = mapper;
        this.clock = clock;
        this.kafkaTemplate = kafkaTemplate;
    }

    protected abstract String stageName();
    protected abstract PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx);

    @KafkaListener(
        topics = "${heirloom.pipeline.kafka.topic-events}",
        groupId = "#{T(com.heirloom.pipeline.kafka.KafkaTopics).groupIdForStage(stageName())}"
    )
    public void onEvent(PipelineEvent event) {
        String name = stageName();

        // 0. listener 只处理匹配 event.type() 的事件（消费端过滤，符合 ADR-039）
        if (!expectedEventType(name).equals(event.type())) {
            log.debug("Listener for stage {} skipping event of type {}", name, event.type());
            return;
        }

        // 1. 幂等检查
        if (execRepo.existsByInputEventIdAndStageNameAndStatus(
                event.eventId(), name, "COMPLETED")) {
            log.debug("Skipping already-completed event {} for stage {}", event.eventId(), name);
            return;
        }

        // 2. 更新 run + stage 状态为 RUNNING。stage 行不存在则 lazy 创建（避免与 Projector 顺序竞争）
        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow();
        run.setStatus(PipelineStatus.RUNNING);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);

        var stage = ensureStage(event.runUuid(), name);
        stage.setStatus(PipelineStatus.RUNNING);
        stage.setAttempts(stage.getAttempts() + 1);
        stage.setStartedAt(clock.instant());
        stageRepo.save(stage);

        // 3. 调用子类业务逻辑
        var ctx = new PipelineContext(event.runUuid(), event.tenantId(), event.sourceFqn(),
            event.correlationId(), name, stage.getAttempts(),
            clock.instant(), clock);
        try {
            PipelineEvent nextEvent = applyStage(event, ctx);

            // 4a. 成功
            stage.setStatus(PipelineStatus.COMPLETED);
            stage.setCompletedAt(clock.instant());
            stageRepo.save(stage);
            execRepo.save(new PipelineStageExecutionEntity(
                event.eventId(), name, "COMPLETED",
                nextEvent != null ? nextEvent.eventId() : null));

            if (nextEvent != null) {
                kafkaTemplate.send(eventsTopic,
                    nextEvent.tenantId() + "::" + nextEvent.sourceFqn(),
                    nextEvent);
            } else {
                if (allStagesComplete(event.runUuid())) {
                    run.setStatus(PipelineStatus.COMPLETED);
                    run.setCompletedAt(clock.instant());
                    run.setUpdatedAt(clock.instant());
                    runRepo.save(run);
                }
            }
        } catch (RecoverableFailure rf) {
            handleRecoverable(event, stage, rf);
        } catch (FatalFailure | PipelineFailure pf) {
            handleFatal(event, stage, pf.getMessage());
        } catch (Exception e) {
            handleFatal(event, stage, "unexpected: " + e.getMessage());
        }
    }

    /**
     * Stage row lazy upsert。避免依赖 Projector 先创建（consumer group 间无顺序保证）。
     */
    private PipelineStageStatusEntity ensureStage(UUID runUuid, String stageName) {
        return stageRepo.findByRunUuidAndStageName(runUuid, stageName).orElseGet(() -> {
            var s = new PipelineStageStatusEntity();
            s.setRunUuid(runUuid);
            s.setStageName(stageName);
            s.setStatus(PipelineStatus.PENDING);
            s.setAttempts(0);
            s.setMaxAttempts(3);
            return stageRepo.save(s);
        });
    }

    /** 推断 stage 期望的 event type（用于消费端过滤，避免 listener 消费其他 stage 的事件） */
    private PipelineEventType expectedEventType(String stageName) {
        return switch (stageName) {
            case "ingestion" -> PipelineEventType.INGESTION_REQUESTED;
            case "discovery" -> PipelineEventType.RAW_DATA_INGESTED;
            case "profiling" -> PipelineEventType.SCHEMA_DISCOVERED;
            case "alignment" -> PipelineEventType.DATA_PROFILED;
            default -> throw new IllegalStateException("unknown stage: " + stageName);
        };
    }

    private void handleRecoverable(PipelineEvent event,
                                    PipelineStageStatusEntity stage,
                                    RecoverableFailure rf) {
        if (stage.getAttempts() >= stage.getMaxAttempts()) {
            handleFatal(event, stage, "max attempts: " + rf.getMessage());
            return;
        }
        long backoffSec = Math.min((long) Math.pow(2, stage.getAttempts()) * 10, 300);
        stage.setStatus(PipelineStatus.RETRYING);
        stage.setNextRetryAt(clock.instant().plusSeconds(backoffSec));
        stage.setLastError(rf.getMessage());
        stageRepo.save(stage);

        // 立即重发（同 partition key 保证顺序）
        kafkaTemplate.send(eventsTopic,
            event.tenantId() + "::" + event.sourceFqn(), event);
        log.info("Stage {} retry {} for event {} (next in {}s)",
            stage.getStageName(), stage.getAttempts(), event.eventId(), backoffSec);
    }

    private void handleFatal(PipelineEvent event,
                               PipelineStageStatusEntity stage, String error) {
        // 双写 DLQ：DB 表（query 真相）+ topic（replay 流）。topic 先发（廉价、快），
        // 表写失败时不重试 topic（replay 流会缺一条但 ops 可从 log 找回）
        kafkaTemplate.send(dlqTopic,
            event.tenantId() + "::" + event.sourceFqn(), event);

        var dlq = new DeadLetterEntity();
        dlq.setRunUuid(event.runUuid());
        dlq.setTenantId(event.tenantId());
        dlq.setSourceFqn(event.sourceFqn());
        dlq.setStageName(stage.getStageName());
        dlq.setEventType(event.type().name());
        dlq.setAttempts(stage.getAttempts());
        dlq.setLastError(error);
        try {
            dlq.setPayload(mapper.writeValueAsString(event));
        } catch (Exception e) {
            dlq.setPayload("{\"error\":\"serialize failed\"}");
        }
        dlqRepo.save(dlq);

        stage.setStatus(PipelineStatus.DEAD_LETTER);
        stage.setLastError(error);
        stageRepo.save(stage);

        var run = runRepo.findByRunUuid(event.runUuid()).orElseThrow();
        run.setStatus(PipelineStatus.DEAD_LETTER);
        run.setUpdatedAt(clock.instant());
        runRepo.save(run);
    }

    /**
     * 检查 ALL 4 个 stage 是否都 COMPLETED（而非"现存 row 全 COMPLETED"）。
     * 解决 C2: stub listener return null 时若只有 2 个 stage 行存在（ingestion+discovery），
     * "现存全 COMPLETED" 会误判 run 完成。"必须有 4 个 stage 行且都 COMPLETED" 才算完成。
     */
    private boolean allStagesComplete(UUID runUuid) {
        var stages = stageRepo.findByRunUuid(runUuid);
        if (stages.size() != ALL_STAGE_NAMES.size()) {
            return false;
        }
        return stages.stream()
            .allMatch(s -> s.getStatus() == PipelineStatus.COMPLETED)
            && ALL_STAGE_NAMES.stream().allMatch(required ->
                stages.stream().anyMatch(s -> s.getStageName().equals(required)));
    }
}
```

### 10.1 重试策略（Phase 7b 简化）

- 不实现真正的延迟重试（Kafka 没有原生延迟队列）
- Recoverable：立即重发到 events topic；Kafka 内置 `DefaultErrorHandler` + `FixedBackOff` 处理 broker 暂时不可达
- `stage.maxAttempts` 控制重试上限；超限 → DLQ
- Phase 7c+ 可升级：加重试 topic + 延迟消费者

---

## 11. 4 个 Stage Listener

### 11.1 PipelineIngestionListener（真实实现）

```java
@Component
public class PipelineIngestionListener extends StageConsumerTemplate {

    private final PipelineIngestionStage stage;

    public PipelineIngestionListener(PipelineRunJpaRepository runRepo,
                                      PipelineStageStatusJpaRepository stageRepo,
                                      PipelineStageExecutionJpaRepository execRepo,
                                      DeadLetterJpaRepository dlqRepo,
                                      PipelineResultJpaRepository resultRepo,
                                      ObjectMapper mapper,
                                      Clock clock,
                                      KafkaTemplate<String, PipelineEvent> kafkaTemplate,
                                      PipelineIngestionStage stage) {
        super(runRepo, stageRepo, execRepo, dlqRepo, resultRepo, mapper, clock, kafkaTemplate);
        this.stage = stage;
    }

    @Override
    protected String stageName() { return "ingestion"; }

    @Override
    protected PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx) {
        return stage.apply(input, ctx);  // 委派给 7a 的 @Component PipelineIngestionStage bean
    }
}
```

### 11.2-11.4 Discovery/Profiling/Alignment Listener（stub for 7c）

**关键：** stub listener **不在 applyStage 返回 null**，而是把事件"转发"到下一阶段的事件类型，让完整事件链能跑到 alignment。这样 stub 也能验证 Kafka 全链路，Phase 7c 替换为真实逻辑时仅改 applyStage。

```java
@Component
public class PipelineDiscoveryListener extends StageConsumerTemplate {

    private static final Logger log = LoggerFactory.getLogger(PipelineDiscoveryListener.class);

    public PipelineDiscoveryListener(PipelineRunJpaRepository runRepo,
                                      PipelineStageStatusJpaRepository stageRepo,
                                      PipelineStageExecutionJpaRepository execRepo,
                                      DeadLetterJpaRepository dlqRepo,
                                      PipelineResultJpaRepository resultRepo,
                                      ObjectMapper mapper,
                                      Clock clock,
                                      KafkaTemplate<String, PipelineEvent> kafkaTemplate) {
        super(runRepo, stageRepo, execRepo, dlqRepo, resultRepo, mapper, clock, kafkaTemplate);
    }

    @Override
    protected String stageName() { return "discovery"; }

    @Override
    protected PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx) {
        log.warn("PipelineDiscoveryListener is a Phase 7c stub. Forwarding event {} as SchemaDiscovered.",
            input.eventId());
        // Phase 7c: 改为 stage.apply(input, ctx)
        var rawIngested = (RawDataIngested) input;
        return new SchemaDiscovered(
            rawIngested.ingestedTableFqns(), rawIngested.ingestedTableFqns().size(),
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), java.time.Instant.now(), 1, "{}");
    }
}
```

```java
@Component
public class PipelineProfilingListener extends StageConsumerTemplate {

    private static final Logger log = LoggerFactory.getLogger(PipelineProfilingListener.class);

    public PipelineProfilingListener(... 同 Discovery 构造 ...) { super(...); }

    @Override
    protected String stageName() { return "profiling"; }

    @Override
    protected PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx) {
        log.warn("PipelineProfilingListener is a Phase 7c stub. Forwarding event {} as DataProfiled.",
            input.eventId());
        var discovered = (SchemaDiscovered) input;
        return new DataProfiled(
            discovered.discoveredTableFqns(), discovered.discoveredTableFqns().size(), 0.0,
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), java.time.Instant.now(), 1, "{}");
    }
}
```

```java
@Component
public class PipelineAlignmentListener extends StageConsumerTemplate {

    private static final Logger log = LoggerFactory.getLogger(PipelineAlignmentListener.class);

    public PipelineAlignmentListener(... 同 Discovery 构造 ...) { super(...); }

    @Override
    protected String stageName() { return "alignment"; }

    @Override
    protected PipelineEvent applyStage(PipelineEvent input, PipelineContext ctx) {
        log.warn("PipelineAlignmentListener is a Phase 7c stub. Returning SemanticAligned (terminal).",
            input.eventId());
        return new SemanticAligned(
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), java.time.Instant.now(), 1, "{}");
    }
}
```

**Phase 7b 端到端流程（带 stub 转发）：**

```
POST /v1/pipeline/runs
  → PipelineService.startRun 创建 run（PENDING）+ publish IngestionRequested to Kafka
  → Projector 收 IngestionRequested → run = RUNNING + stage "ingestion" 行
  → IngestionListener 收 IngestionRequested → stage.apply() → 发 RawDataIngested
  → Projector 收 RawDataIngested → stage "discovery" 行（lazy）
  → DiscoveryListener（stub）收 RawDataIngested → return SchemaDiscovered → 发
  → Projector 收 SchemaDiscovered → stage "profiling" 行
  → ProfilingListener（stub）收 SchemaDiscovered → return DataProfiled → 发
  → Projector 收 DataProfiled → stage "alignment" 行
  → AlignmentListener（stub）收 DataProfiled → return SemanticAligned → 发
  → SemanticAligned 进 events topic，无 listener 订阅（阶段 5+ 才消费）
    → 5 秒后 Kafka retention / 或 Phase 7b 接受该事件无人消费
    → run 永远不会 allStagesComplete → 永远 RUNNING
  
  ⚠️ 接受此行为：Phase 7b 验证 Kafka 全链路 + 单 stage 真实工作；
    run 不会自然 COMPLETED 是 stub 链路的设计结果。Phase 7c 替换 stub 后行为正确。
```

**或者更干净的 Phase 7b demo：Discovery/Profiling/Alignment stub **直接 return null（不转发）**，这样 run 会"提前"COMPLETED（如 C2 描述）。但这违背"4 个 stage 都该有真实 listener"的意图。**

**采用：stub 转发版本（上面代码）。run 行为：直到 SemanticAligned 被发出并被 Projector 收到 + Alignment stage COMPLETED，所有 4 stage 都 COMPLETED → run = COMPLETED。**

注：SemanticAligned 发出后无 listener 处理，但 Projector 收到 → 创建/确认 stage 行。AlignmentListener 收到（因 stageName="alignment" 监听 DATA_PROFILED，但事件是 SEMANTIC_ALIGNED → 过滤掉 skip）。等等——AlignmentListener 监听 DATA_PROFILED，SemanticAligned 不会被它消费。这意味着 alignment 阶段停留在 DATA_PROFILED 的处理完，不会被 SemanticAligned 触发再次消费。

修正：AlignmentListener 的 expectedEventType 应为 DATA_PROFILED。stub 返回 SemanticAligned 后该事件被发出但无人消费（无 stage 监听 SEMANTIC_ALIGNED），run 不会完成。

**最终决定：** 接受 Phase 7b demo 行为：
- Ingestion 真实工作 → 发 RawDataIngested
- Discovery stub 转发 → 发 SchemaDiscovered
- Profiling stub 转发 → 发 DataProfiled
- Alignment stub 转发 → 发 SemanticAligned
- SemanticAligned 进入 events topic，无 listener 消费 → run 永远停留在 alignment 处理完但 SemanticAligned 未消费状态

为验证 Phase 7b 框架 OK，**E2E 测试只验证到 "alignment stage COMPLETED"**（通过 GET /runs/{uuid} 看 alignment stage status）—— 不验证 run.status=COMPLETED。Phase 7c 把 stub 替换后，run 会按设计 COMPLETED。

---

## 12. PipelineService 变更

```java
@Transactional
public PipelineRunEntity startRun(String tenantId, String sourceFqn,
                                   List<String> tableFqns, PipelineTriggerType triggerType) {
    // 7a 一样的 active-run 检查 + 创建 pipeline_runs 行（PENDING）
    // ... 略 ...

    // 直接 publish 到 Kafka（替代 7a 的 PipelineEventPublisher.publishIngestionRequested）
    PipelineEvent event = new IngestionRequested(
        tableFqns, UUID.randomUUID(), runUuid, tenantId, sourceFqn,
        correlationId.toString(), Instant.now(), 1, "{}");
    bus.publish(event);  // KafkaBus

    return run;
}
```

**关键一致性：**
- `pipeline_runs` 行创建（事务内）+ `bus.publish()`（事务外，异步）—— 不是同一事务
- 如果 Kafka send 失败：run 行已创建但事件未发出 → run 永远 PENDING（GET 可见，用户重触发）
- Phase 7c 可加重试 / DLQ for producer

---

## 13. KafkaProducerConfig / KafkaConsumerConfig

### 13.1 KafkaProducerConfig

```java
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, PipelineEvent> producerFactory(KafkaProperties props) {
        Map<String, Object> config = new HashMap<>(props.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            com.heirloom.pipeline.kafka.PipelineEventSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, PipelineEvent> kafkaTemplate(
            ProducerFactory<String, PipelineEvent> pf) {
        return new KafkaTemplate<>(pf);
    }
}
```

### 13.2 KafkaConsumerConfig

Spring Boot 自动配置 + `application.yml` 中指定 `value-deserializer` 已足够。无需显式 `ConcurrentKafkaListenerContainerFactory` bean（除非需要自定义并发度，Phase 7b 用默认 1 listener 1 container）。

如果需要并发调优（如多 partition 时多个 container 并行消费）：

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, PipelineEvent> kafkaListenerContainerFactory(
        ConsumerFactory<String, PipelineEvent> cf) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, PipelineEvent>();
    factory.setConsumerFactory(cf);
    factory.setConcurrency(3);  // 每 stage 3 个并发消费者
    return factory;
}
```

Phase 7b 暂用默认。Phase 7c 视 partition 数调整。

### 13.3 KafkaTopics（group id 常量）

```java
public final class KafkaTopics {

    public static final String EVENTS = "heirloom.pipeline.events";
    public static final String DLQ = "heirloom.pipeline.dlq";

    public static final String GROUP_PROJECTOR = "heirloom-pipeline-projector";
    public static final String GROUP_STAGE_PREFIX = "heirloom-pipeline-stage-";

    public static String groupIdForStage(String stageName) {
        return GROUP_STAGE_PREFIX + stageName;
    }

    private KafkaTopics() {}
}
```

`StageConsumerTemplate` 中 `groupId = "#{T(com.heirloom.pipeline.kafka.KafkaTopics).groupIdForStage(stageName())}"` 引用此常量（SpEL）。

---

## 14. HeirloomApplication.java

无需修改。Spring Boot `@SpringBootApplication` + `spring-boot-starter` 自动启用 spring-kafka 自动配置 + `KafkaAdmin` 自动建 topic。

需要确保：项目 `pom.xml` 有 `spring-kafka` 依赖。

---

## 15. 测试策略

| 层 | 类型 | 范围 |
|---|---|---|
| Unit | JUnit + Mockito | KafkaBus.publish() 调 kafkaTemplate.send() 且 partition key 格式正确 |
| Unit | JUnit | PipelineEventSerializer / Deserializer roundtrip（5 种类型） |
| Unit | JUnit | StageConsumerTemplate.handleRecoverable / handleFatal 路径（mock repo + mock kafkaTemplate） |
| Unit | JUnit | Projector 状态更新路径（mock repo） |
| Integration | @SpringBootTest + @EmbeddedKafka | KafkaBusTest：publish 后用 test consumer 接收验证 topic 内容 |
| Integration | @EmbeddedKafka | ProjectorTest：发 IngestionRequested → 验证 pipeline_runs 状态 + stage 行创建 |
| Integration | @EmbeddedKafka | IngestionListenerTest：发 IngestionRequested → 验证 stage bean 被调用 + RawDataIngested 发回 topic + DB 状态 |
| Integration | @EmbeddedKafka | E2ETest：POST /v1/pipeline/runs → 等 → GET /runs/{uuid} → ingestion COMPLETED |
| Integration | @EmbeddedKafka | DLQTest：mock stage bean 抛 FatalFailure → 验证 DLQ 表 + DLQ topic 都有 |

### 15.1 测试关键点

- @EmbeddedKafka 启动 ~2 秒，5 个 consumer group 都注册
- Awaitility 等 consumer 处理消息
- 用 `kafkaTemplate.send().get(5, TimeUnit.SECONDS)` 验证 send 成功
- 用 `@EmbeddedKafka(partitions = 3, topics = {...})` 配置测试 topic
- test consumer 可用 `EmbeddedKafkaBroker.consumeFromAnEmbeddedTopic(...)` 或独立 test consumer group

---

## 16. 任务清单

```
Phase 7b — Kafka Adapter
├── 7b.0  pom.xml 加 spring-kafka 依赖
├── 7b.1  application.yml 加 Kafka 配置
├── 7b.2  KafkaTopics 常量类
├── 7b.3  KafkaTopicConfig（NewTopic beans）
├── 7b.4  PipelineEventSerializer + PipelineEventDeserializer
├── 7b.5  KafkaProducerConfig（ProducerFactory + KafkaTemplate）
├── 7b.6  KafkaBus 实现 PipelineEventBus
├── 7b.7  V28__drop_pipeline_outbox.sql
├── 7b.8  删除 InProcessBus / PipelineEventPublisher / PipelineOutboxEntity / PipelineOutboxJpaRepository / V25 SQL
├── 7b.9  删除 OutboxProcessor / InProcessBusTest
├── 7b.10 PipelineService.startRun 改为 KafkaBus.publish()
├── 7b.11 StageConsumerTemplate 抽象基类
├── 7b.12 PipelineEventProjector
├── 7b.13 PipelineIngestionListener（真实实现）
├── 7b.14 PipelineDiscoveryListener（stub）
├── 7b.15 PipelineProfilingListener（stub）
├── 7b.16 PipelineAlignmentListener（stub）
├── 7b.17 KafkaBusTest 单元
├── 7b.18 PipelineEventSerializer/DeserializerTest 单元
├── 7b.19 PipelineEventProjectorTest @EmbeddedKafka
├── 7b.20 PipelineIngestionListenerTest @EmbeddedKafka
├── 7b.21 PipelineKafkaE2EIntegrationTest @EmbeddedKafka
├── 7b.22 DLQ 路径测试 @EmbeddedKafka
└── 7b.23 验证：reactor compile + core 单元 + server 单元 + 集成测试全跑
```

---

## 17. 风险与缓解

| 风险 | 缓解 |
|---|---|
| @EmbeddedKafka 与真实 Kafka 行为差异（如 message size limit） | 仅用于单元/集成测试；生产前用 Testcontainers smoke（不在 7b 范围） |
| Kafka 启动顺序：KafkaAdmin 必须在 listener 启动前建好 topic | Spring Kafka 自动配置保证：`KafkaAdmin` bean 早于 listener container |
| producer 发送失败但 run 已创建（7a 的 InProcessBus.publish 在事务内，新设计不在） | run 永远 PENDING；GET 端点可见；用户重新触发；Phase 7c 加 producer 重试 |
| Stage consumer 抛异常时 offset commit 行为 | Spring Kafka `DefaultErrorHandler` 默认 N 次重试后 commit；Phase 7b 简化（超 maxAttempts 直接 DLQ） |
| Phase 7a 单元测试因 InProcessBus 删除而 broken | 7b.9 一并删 InProcessBusTest；其他测试不受影响 |
| Spring Kafka 的 `@KafkaListener` 启动顺序：先 KafkaAdmin 建 topic 再 consumer 启动 | Spring Kafka 自动确保：`KafkaAdmin` 初始化时 NewTopic beans 注册，listener container 启动前完成 |

---

## 18. 相关 ADR

- [ADR-038](../../adr/038-raw-to-ontology-pipeline.md) — raw → ontology 管线（架构来源）
- [ADR-039](../../adr/039-kafka-pipeline-topics.md) — Kafka topic 组织策略（Phase 7b 实现）
- [ADR-046](../../adr/046-metadata-first-mvp.md) — 元数据优先 MVP

## 19. Phase 7a → Phase 7b 迁移

| Phase 7a 资产 | Phase 7b 处理 |
|---|---|
| `PipelineEventBus` 接口 | 保留 |
| `PipelineEvent` / records | 保留（无变更） |
| `PipelineStage` / `PipelineStageRegistry` / `PipelineContext` | 保留 |
| `PipelineFailure` / `RecoverableFailure` / `FatalFailure` | 保留 |
| `PipelineRun` / `PipelineStageStatus` 接口 | 保留 |
| `PipelineStatus` / `PipelineTriggerType` enum | 保留 |
| `PipelineService` | 改（startRun 调 KafkaBus） |
| `PipelineOrchestrator` + 4 `Pipeline*Stage` beans | 保留（被 listener 委派调用） |
| `PipelineRunEntity` / `PipelineStageStatusEntity` / `PipelineResultEntity` / `DeadLetterEntity` / `PipelineStageExecutionEntity` | 保留 |
| `Pipeline*JpaRepository`（非 outbox） | 保留 |
| `PipelineResource` REST + DTOs | 保留 |
| `InProcessBus` / `PipelineEventPublisher` | **删除** |
| `OutboxProcessor` | **删除** |
| `pipeline_outbox` 表 / `PipelineOutboxEntity` / `PipelineOutboxJpaRepository` / V25 SQL | **删除（V28 DROP）** |
| `InProcessBusTest` | **删除** |