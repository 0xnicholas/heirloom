# Heirloom Pipeline 基础建设 — 设计规格

**日期**: 2026-07-16
**状态**: Draft
**参考**: ADR-038、ADR-039、ADR-046
**范围**: Phase 7a（前 4 阶段骨架 + 事务性 outbox）

---

## 1. 背景

Phase 5 引入 DuckDB raw store + Query Router，Phase 6 补齐 metadata catalog。但 raw table 到 ontology 实体的映射完全手动。ADR-038 提出 8 阶段事件驱动管线，但全量实施成本过高且依赖 Kafka。

Phase 7a 在不引入 Kafka 的前提下，建立可插拔管线骨架并实施前 4 阶段。核心创新：**采用 Transactional Outbox 模式**保证进程崩溃不丢消息，**后台 OutboxProcessor** 异步派发事件，实现真正的 202 异步语义。

### 1.1 与 ADR-038 的差异

| 主题 | ADR-038 | Phase 7a |
|---|---|---|
| 事件总线 | Kafka | PipelineEventBus 接口 + InProcessBus（DB outbox 后端） |
| 派发模型 | 消费者拉取 | OutboxProcessor 定时拉取 |
| 持久化 | Kafka topic | pipeline_outbox 表 |
| 事务性 | 消费者幂等 | outbox + run 单事务原子写入 |
| 崩溃恢复 | Kafka offset 提交 | `SELECT FOR UPDATE SKIP LOCKED` + 重放未完成 outbox 记录 |

接口 `PipelineEventBus` 抽象这一切，未来 Kafka adapter 可实现同一接口，零业务代码改动。

### 1.2 与 ADR-038 一致的部分

- 8 阶段顺序与事件命名
- DLQ 失败隔离策略（pipeline_dead_letter 表）
- Event Log 与管线事件职责分工

---

## 2. 设计目标

### 2.1 Phase 7a 必达

- 4 阶段管线：Ingestion → Discovery → Profiling → Alignment
- `PipelineEventBus` 接口（heirloom-core）+ InProcessBus 实现（heirloom-server）
- Transactional Outbox：run + outbox event 单事务原子写入
- OutboxProcessor：@Scheduled 拉取未派发事件，`SELECT FOR UPDATE SKIP LOCKED` 防多实例冲突
- per-stage 状态追踪（pipeline_run_stages 表）
- per-stage 重试计数（避免 attempts 全局累积）
- DB-backed DLQ + 重放列表 API
- 现有 `DiscoveryResource` 改为触发管线，REST 202 Accepted
- Stage 结果持久化（pipeline_run_results 表）— 后续阶段可消费
- 完整 REST API：`/v1/pipeline/runs`、`/v1/pipeline/dead-letter`
- 单元 + Testcontainers 集成测试

### 2.2 非目标

- 阶段 5-8（Entity Resolution / Ontology Proposal / Governance / Mapping & Publish）
- Kafka adapter（接口已就位，实现留 Phase 7b 或 8）
- 调度器触发、CDC 事件触发
- DLQ 重放 UI
- 多租户隔离（字段已预留，默认 `"default"`）
- 取消 RUNNING run

---

## 3. 架构与模块布局

### 3.1 接口模块（heirloom-core，零 Spring 依赖）

```
heirloom-core/src/main/java/com/heirloom/core/pipeline/
├── PipelineEventBus.java          # 仅接口：publish() / subscribe() / start()
├── PipelineEvent.java             # sealed interface
├── PipelineEventType.java         # enum（5 个事件类型；失败事件不入 event 流，写 DLQ + 日志）
├── PipelineStage.java             # @FunctionalInterface
├── PipelineStageRegistry.java     # 注册表（PipelineStageRegistry SPI）
├── PipelineRun.java               # entity interface（运行级状态）
├── PipelineStageStatus.java       # entity interface（阶段级状态）
├── PipelineStatus.java            # enum (PENDING | RUNNING | COMPLETED | RETRYING | DEAD_LETTER)
├── PipelineTriggerType.java       # enum (MANUAL | DISCOVERY_AUTO)
├── PipelineContext.java           # record
└── PipelineFailure.java           # sealed abstract class extends RuntimeException
    ├── RecoverableFailure.java    # final 子类
    └── FatalFailure.java          # final 子类
```

**Core 边界约束：**
- `PipelineEvent.payload` 是 `String`（原始 JSON），不依赖 Jackson
- 不含任何持久化 / 调度 / JPA 概念
- 持久化与 outbox 在 heirloom-server 的 InProcessBus 实现中

### 3.2 实现模块（heirloom-server，Spring + JPA）

```
heirloom-server/src/main/java/com/heirloom/pipeline/
├── bus/
│   ├── InProcessBus.java                     # PipelineEventBus 实现（outbox 持久化）
│   └── PipelineEventPublisher.java           # 外部触发入口
├── stages/
│   ├── PipelineIngestionStage.java           # 包装 DuckDbSyncService（per table）
│   ├── PipelineDiscoveryStage.java           # 包装 DiscoveryService
│   ├── PipelineProfilingStage.java           # 包装 ProfilingService
│   └── PipelineAlignmentStage.java           # 包装 AlignmentService
├── orchestrator/
│   └── PipelineOrchestrator.java             # @PostConstruct 装配订阅
├── processor/
│   └── OutboxProcessor.java                  # @Scheduled 拉取 outbox 派发
├── persistence/
│   ├── PipelineRunEntity.java                # 运行级 JPA
│   ├── PipelineStageStatusEntity.java        # 阶段级 JPA
│   ├── PipelineResultEntity.java             # 阶段结果持久化
│   ├── PipelineOutboxEntity.java             # outbox 表
│   ├── DeadLetterEntity.java                 # 死信
│   └── (各 JpaRepository + Repository)
├── service/
│   └── PipelineService.java                  # start / get / list
└── web/
    ├── PipelineResource.java                 # REST (Spring MVC)
    └── dto/                                  # 请求/响应 DTO
```

### 3.3 Flyway 迁移

```
heirloom-server/src/main/resources/db/migration/
├── V22__create_pipeline_runs.sql
├── V23__create_pipeline_run_stages.sql
├── V24__create_pipeline_run_results.sql
├── V25__create_pipeline_outbox.sql
├── V26__create_pipeline_dead_letter.sql
└── V27__create_pipeline_stage_executions.sql
```

---

## 4. 核心接口契约（heirloom-core）

### 4.1 PipelineEventBus

```java
public interface PipelineEventBus {
    /**
     * 将事件加入 outbox（同步事务）。调用方的事务边界决定可见性。
     * 不直接派发 — 由 OutboxProcessor 异步拉取。
     */
    void publish(PipelineEvent event);

    /** 注册订阅者（按 PipelineEventType 路由） */
    void subscribe(PipelineEventType type, PipelineStage subscriber);

    /** 启动时由 Spring 触发：OutboxProcessor 开始 @Scheduled 拉取 */
    void start();
}
```

### 4.2 PipelineEvent（sealed interface）

```java
public sealed interface PipelineEvent
    permits IngestionRequested, RawDataIngested, SchemaDiscovered,
            DataProfiled, SemanticAligned {

    UUID eventId();           // ADR-038 要求
    UUID runUuid();
    String tenantId();
    String sourceFqn();       // 统一 sourceFqn（不是 sourceId）
    String correlationId();
    PipelineEventType type();
    Instant occurredAt();
    int payloadVersion();     // ADR-039 schema 版本
    String payload();         // 原始 JSON 字符串（不依赖 Jackson in core）
}
```

每个事件是 record（实现统一 envelope）：

```java
public record IngestionRequested(
    List<String> tableFqns,            // 已知 table 列表（用户/REST 提供）
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent { ... }

public record RawDataIngested(
    List<String> ingestedTableFqns, Instant syncedAt,
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent { ... }

public record SchemaDiscovered(
    List<String> discoveredTableFqns, int tableCount,
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent { ... }

public record DataProfiled(
    List<String> profiledTableFqns, int profiledCount, double avgQualityScore,
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent { ... }

public record SemanticAligned(
    UUID eventId, UUID runUuid, String tenantId, String sourceFqn,
    String correlationId, Instant occurredAt, int payloadVersion, String payload
) implements PipelineEvent { ... }
```

注：`SemanticAligned` 不带具体结果 — AlignmentMap 持久化到 `pipeline_run_results` 表，事件只通知下游"对齐已完成"。

### 4.3 PipelineStage

```java
@FunctionalInterface
public interface PipelineStage {
    /** 处理输入事件，返回输出的下一个事件（null 表示终止） */
    PipelineEvent apply(PipelineEvent input, PipelineContext context)
        throws PipelineFailure;
}
```

### 4.4 PipelineFailure（sealed abstract class）

```java
public sealed abstract class PipelineFailure extends RuntimeException
    permits RecoverableFailure, FatalFailure {

    protected PipelineFailure(String message) { super(message); }
    protected PipelineFailure(String message, Throwable cause) { super(message, cause); }
}

public final class RecoverableFailure extends PipelineFailure {
    public RecoverableFailure(String message) { super(message); }
    public RecoverableFailure(String message, Throwable cause) { super(message, cause); }
}

public final class FatalFailure extends PipelineFailure {
    public FatalFailure(String message) { super(message); }
    public FatalFailure(String message, Throwable cause) { super(message, cause); }
}
```

### 4.5 PipelineContext

```java
public record PipelineContext(
    UUID runUuid,
    String tenantId,
    String sourceFqn,
    String correlationId,
    String stageName,           // 当前 stage
    int stageAttempt,           // 当前 stage 已尝试次数（从 1 开始）
    Instant stageStartedAt,
    Clock clock                 // 测试时可注入
) {}
```

### 4.6 PipelineStageRegistry

```java
public interface PipelineStageRegistry {
    /** 注册 stage（按 eventType 索引，每个 type 唯一 stage） */
    void register(PipelineEventType type, PipelineStage stage);

    /** 查找 stage */
    Optional<PipelineStage> find(PipelineEventType type);

    /** 启动时由 orchestrator 调用 */
    void start();
}
```

约束：**一个 event type 只能注册一个 stage**（不允许多订阅 fan-out，避免不确定性）。新增阶段时只需注册新的 event type → stage 映射。

### 4.7 PipelineRun（entity interface）

```java
public interface PipelineRun {
    UUID getRunUuid();
    String getTenantId();
    String getSourceFqn();
    PipelineStatus getStatus();
    String getCorrelationId();
    PipelineTriggerType getTriggerType();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    Instant getCompletedAt();
}
```

注：**运行级无 attempts 字段**。attempts 在阶段级（pipeline_run_stages）。

### 4.8 PipelineStageStatus（entity interface）

```java
public interface PipelineStageStatus {
    UUID getRunUuid();
    String getStageName();
    PipelineStatus getStatus();
    int getAttempts();
    int getMaxAttempts();
    Instant getStartedAt();
    Instant getCompletedAt();
    Instant getNextRetryAt();
    String getLastError();
}
```

---

## 5. 数据模型

### 5.1 V22 — pipeline_runs（运行级）

```sql
CREATE TABLE pipeline_runs (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL UNIQUE,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  source_fqn VARCHAR(512) NOT NULL,        -- sourceId 统一改为 sourceFqn
  status VARCHAR(32) NOT NULL,
  correlation_id UUID NOT NULL,
  trigger_type VARCHAR(32) NOT NULL,
  table_fqns TEXT,                          -- 触发时的 tableFQN 列表（逗号分隔）
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);

-- 并发唯一：同 (tenant_id, sourceFqn) 同一时刻只能有一个 active run
CREATE UNIQUE INDEX uq_pipeline_runs_active
  ON pipeline_runs (tenant_id, source_fqn)
  WHERE status IN ('PENDING','RUNNING','RETRYING');

CREATE INDEX idx_pipeline_runs_status ON pipeline_runs (status);
```

### 5.2 V23 — pipeline_run_stages（阶段级）

```sql
CREATE TABLE pipeline_run_stages (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL REFERENCES pipeline_runs(run_uuid) ON DELETE CASCADE,
  stage_name VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  next_retry_at TIMESTAMPTZ,
  last_error TEXT,
  UNIQUE (run_uuid, stage_name)
);

CREATE INDEX idx_stages_retry
  ON pipeline_run_stages (status, next_retry_at)
  WHERE status = 'RETRYING';
```

**per-stage attempts**：避免全局累积。`max_attempts` 默认 3，可配置。

### 5.3 V24 — pipeline_run_results（阶段结果持久化）

```sql
CREATE TABLE pipeline_run_results (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL REFERENCES pipeline_runs(run_uuid) ON DELETE CASCADE,
  stage_name VARCHAR(64) NOT NULL,
  result_type VARCHAR(64) NOT NULL,        -- 'table_entity_list', 'alignment_map', ...
  result JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (run_uuid, stage_name)
);
```

每个阶段写自己的结果。下游阶段从该表读取上游结果。例如 Alignment 阶段读 pipeline_run_results 里 Discovery 的 TableEntity 列表，结合 Profiling 的 column_profile，产出 AlignmentMap 持久化回此表。

### 5.4 V25 — pipeline_outbox（事务性 outbox）

```sql
CREATE TABLE pipeline_outbox (
  id BIGSERIAL PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  run_uuid UUID NOT NULL REFERENCES pipeline_runs(run_uuid) ON DELETE CASCADE,
  event_type VARCHAR(64) NOT NULL,
  payload JSONB NOT NULL,                  -- 完整 PipelineEvent 序列化
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- PENDING | CLAIMED | DISPATCHED | FAILED
  claimed_at TIMESTAMPTZ,
  claimed_by VARCHAR(128),                 -- 实例标识（hostname + UUID）
  claimed_until TIMESTAMPTZ,                -- lease 超时
  not_before TIMESTAMPTZ,                   -- RETRYING 事件的最早可派发时间
  dispatched_at TIMESTAMPTZ,
  attempts INT NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_pending
  ON pipeline_outbox (status, claimed_until, not_before, created_at)
  WHERE status IN ('PENDING','CLAIMED');
```

**OutboxProcessor 工作流：**
```sql
-- 单条语句原子声明一批事件（新 PENDING + 过期 CLAIMED 一起回收）
UPDATE pipeline_outbox
SET status='CLAIMED',
    claimed_by=?,
    claimed_until=now() + interval '60 seconds',
    claimed_at=COALESCE(claimed_at, now())
WHERE id IN (
  SELECT id FROM pipeline_outbox
  WHERE status IN ('PENDING','CLAIMED')
    AND (claimed_until IS NULL OR claimed_until < now())
    AND (not_before IS NULL OR not_before <= now())
  ORDER BY created_at
  LIMIT 50
  FOR UPDATE SKIP LOCKED
)
RETURNING id, payload;
```

崩溃恢复：实例崩溃后，其 CLAIMED 行的 `claimed_until` 超时后被任意存活实例回收。`not_before` 让 RETRYING 事件在指定时间前不被派发。

### 5.5 V26 — pipeline_dead_letter

```sql
CREATE TABLE pipeline_dead_letter (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL REFERENCES pipeline_runs(run_uuid) ON DELETE CASCADE,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  source_fqn VARCHAR(512) NOT NULL,
  stage_name VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  attempts INT NOT NULL,
  last_error TEXT,
  payload JSONB NOT NULL,
  failed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  replayed_at TIMESTAMPTZ,
  replayed_by VARCHAR(255)
);

CREATE INDEX idx_dlq_unreplayed
  ON pipeline_dead_letter (failed_at DESC)
  WHERE replayed_at IS NULL;
```

### 5.6 V27 — pipeline_stage_executions（幂等追踪）

```sql
CREATE TABLE pipeline_stage_executions (
  input_event_id UUID NOT NULL,
  stage_name VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,         -- COMPLETED | FAILED
  output_event_id UUID,                -- 该 stage 产出的下一个 event_id
  completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (input_event_id, stage_name)
);
```

幂等语义：同一 `(input_event_id, stage_name)` 只能成功一次。OutboxProcessor 在执行 stage 前先 SELECT，COMPLETED 则跳过。**Phase 7a 接受 at-least-once**：stage 必须自行实现 §6.6 的幂等机制（upsert by FQN 等），本表提供 hint 层去重。

### 5.7 状态机（运行级）

```
              POST /runs
                │
                ▼
            ┌────────┐
            │ PENDING │ ◄──┐
            └────┬───┘    │ retry（stage attempts < max）
                 │        │
                 ▼        │
            ┌────────┐    │
            │ RUNNING │    │
            └────┬───┘    │
       success │  │ fail  │
              ▼   ▼       │
       ┌──────────┐  ┌────────┐
       │COMPLETED │  │RETRYING│──┘
       └──────────┘  └───┬────┘
                         │ all stages exhausted
                         ▼
                  ┌─────────────┐
                  │ DEAD_LETTER │
                  └─────────────┘
```

`FAILED` 状态移除。终态仅 `COMPLETED` 和 `DEAD_LETTER`。

### 5.8 状态机（阶段级）

每个阶段独立状态：PENDING → RUNNING → (COMPLETED | RETRYING | DEAD_LETTER)。

---

## 6. 事件总线实现（InProcessBus）

### 6.1 publish 行为

```
publish(event) (在调用方事务内执行):
  1. 校验 run 存在且 status ∈ {PENDING, RUNNING, RETRYING}
  2. INSERT INTO pipeline_outbox (event_id, run_uuid, event_type, payload, status='PENDING')
  3. UPDATE pipeline_runs SET status='RUNNING', updated_at=now()
  4. 提交事务
  5. 返回（OutboxProcessor 异步派发）
```

**关键：调用方事务回滚则 outbox 事件也回滚**（保证原子性）。

### 6.1b start 行为（启动恢复）

`InProcessBus.start()` 在应用启动时调用一次：

```
start():
  1. 开启事务
  2. UPDATE pipeline_outbox
       SET status='PENDING', claimed_by=NULL, claimed_until=NULL
     WHERE status='CLAIMED' AND claimed_until < now()
  3. 提交
  4. 启动 OutboxProcessor @Scheduled
```

恢复被崩溃实例留下的 CLAIMED 行（lease 超时后）。

### 6.2 OutboxProcessor 行为

```
@Scheduled(fixedDelayString = "${heirloom.pipeline.outbox-poll-seconds:5s}")
dispatchPending():
  1. 单条 SQL 原子声明一批事件（新 PENDING + 过期 CLAIMED 一起回收）：
     UPDATE pipeline_outbox
     SET status='CLAIMED',
         claimed_by=?,
         claimed_until=now() + interval '60 seconds',
         claimed_at=COALESCE(claimed_at, now())
     WHERE id IN (
       SELECT id FROM pipeline_outbox
       WHERE status IN ('PENDING','CLAIMED')
         AND (claimed_until IS NULL OR claimed_until < now())
         AND (not_before IS NULL OR not_before <= now())
       ORDER BY created_at
       LIMIT 50
       FOR UPDATE SKIP LOCKED
     )
     RETURNING id, event_id, event_type, payload;
  2. 对每条记录：
     a. 反序列化 payload → PipelineEvent
     b. 查 PipelineStageRegistry.find(event.type())
     c. 若 stage 不存在：视为 fatal — 见 step 3h
     d. 幂等检查：SELECT 1 FROM pipeline_stage_executions
         WHERE input_event_id=? AND stage_name=? AND status='COMPLETED'
         若存在：标记 outbox DISPATCHED，跳过 stage 执行（已成功过）
     e. UPDATE pipeline_runs SET status='RUNNING', updated_at=now()
     f. 调用 stage.apply(event, context)
     g. 成功：
        - INSERT INTO pipeline_stage_executions (input_event_id=event.eventId,
          stage_name, status='COMPLETED', output_event_id=<nextEvent.eventId if any>)
        - UPDATE pipeline_run_stages 该 stage 的 status='COMPLETED', completed_at=now()
        - 写 pipeline_run_results（如有）
        - 若 stage 返回 nextEvent != null：
          · INSERT INTO pipeline_outbox (event_id=nextEvent.eventId(), ..., not_before=NULL)
            → 下次 poll 派发（terminal stage 会消费此事件并返回 null）
        - 若 stage 返回 null 且所有 stage 都 COMPLETED：
          UPDATE pipeline_runs SET status='COMPLETED', completed_at=now()
        - UPDATE outbox status='DISPATCHED'
     h. 致命失败（unknown stage 或 FatalFailure 或 attempts ≥ max_attempts）：
        - INSERT INTO pipeline_dead_letter
        - UPDATE pipeline_run_stages 该 stage SET status='DEAD_LETTER'
        - UPDATE pipeline_runs SET status='DEAD_LETTER'
        - INSERT INTO pipeline_stage_executions status='FAILED'
        - UPDATE outbox status='FAILED'
     i. RecoverableFailure 且 stage.attempts < max_attempts：
        - UPDATE pipeline_run_stages SET status='RETRYING', attempts=attempts+1,
          next_retry_at=now() + min(2^attempts * 10s, 5min), last_error=?
        - UPDATE pipeline_runs SET status='RETRYING'
        - INSERT INTO pipeline_outbox 新事件（event_id=newUuid, payload=原 input 序列化），
          status='PENDING', not_before=next_retry_at
        - UPDATE 当前 outbox status='DISPATCHED'
```

**关键约束：**
- `max_attempts` 含初次尝试。`attempts=1` 是首次执行，< 3 表示还能再试 2 次
- RETRYING 事件通过插入新 outbox 行（not_before=nextRetryAt）实现，无需单独 RetryScheduler
- CLAIMED 行通过 poll 查询同时回收（status IN ('PENDING','CLAIMED') + claimed_until 检查）
- 终止事件由 `terminalStage`（no-op）处理，避免 unknown-stage fatal 路径

### 6.3 异常分类

| 异常类型 | 行为 |
|---|---|
| `RecoverableFailure` | 重试（per-stage attempts < max_attempts） |
| `FatalFailure` | 直接 DLQ，无重试 |
| 其他 `RuntimeException` | 包装为 FatalFailure，写入 DLQ |
| `Error`（OOM 等） | 不捕获，进程崩溃；outbox 由下次启动的 recover() 重派 |

### 6.4 阶段适配器（Stage Adapters）

现有服务（`DiscoveryService`、`ProfilingServiceImpl`、`AlignmentServiceImpl`）返回报告对象，不抛异常。Stage 适配器负责：

- 调用现有服务
- 将报告结果写入 `pipeline_run_results`
- 根据报告状态（部分失败 / 全失败）决定抛 `RecoverableFailure` / `FatalFailure` / 成功

```java
@Component
public class PipelineDiscoveryStage implements PipelineStage {
    @Override
    public PipelineEvent apply(PipelineEvent input, PipelineContext ctx) {
        // input 是 RawDataIngested（来自 IngestionStage 输出）
        var rawIngested = (RawDataIngested) input;
        // 通过 sourceFqn 在 SourceRegistry 查找连接配置（不进 run payload）
        var source = sourceRepo.findByFqn(ctx.sourceFqn())
            .orElseThrow(() -> new FatalFailure("source not found: " + ctx.sourceFqn()));
        var report = discoveryService.runDiscovery(source);
        pipelineResultRepo.save(new PipelineResultEntity(
            ctx.runUuid(), "discovery", "discovery_report", report));
        if (report.hasFatalErrors()) {
            throw new FatalFailure("Discovery failed fatally: " + report.errors());
        }
        return new SchemaDiscovered(
            report.discoveredTableFqns(), report.tableCount(),
            UUID.randomUUID(), ctx.runUuid(), ctx.tenantId(), ctx.sourceFqn(),
            ctx.correlationId(), Instant.now(), 1, "{}");
    }
}
```

### 6.5 幂等性要求

每个 stage 必须幂等。**幂等键**：`(input_event_id, stage_name)`，由 `pipeline_stage_executions` 表强制。

| Stage | 幂等机制 |
|---|---|
| PipelineIngestionStage | DuckDB `CREATE TABLE IF NOT EXISTS` + 原子 rename |
| PipelineDiscoveryStage | `TableEntity` upsert by FQN |
| PipelineProfilingStage | 检查 `tableProfile.profiledAt` TTL；同 event_id 跳过 |
| PipelineAlignmentStage | `pipeline_run_results` 写前 delete by (runUuid, stageName) |

### 6.6 终止事件（Terminal Stage）

`SemanticAligned` 是 Phase 7a 的终止事件（无下游 stage 消费）。通过注册 no-op terminal stage 处理：

```java
@Component
public class PipelineOrchestrator {
    @PostConstruct void wire(...) {
        registry.register(INGESTION_REQUESTED, ingestionStage);
        registry.register(RAW_DATA_INGESTED, discoveryStage);
        registry.register(SCHEMA_DISCOVERED, profilingStage);
        registry.register(DATA_PROFILED, alignmentStage);
        // 终止事件：无操作 stage，返回 null → OutboxProcessor 标记 COMPLETED
        registry.register(SEMANTIC_ALIGNED, (event, ctx) -> null);
    }
}
```

Phase 7b+ 引入新 stage 时只需注册新的 event type → stage 映射，终止事件可保留为占位或移除。

---

## 7. 阶段编排

### 7.1 PipelineOrchestrator

见 §6.6 终止事件处理。完整代码见 §6.6 示例。

### 7.2 事件链

```
IngestionRequested  ──▶  [IngestionStage]  ──▶  RawDataIngested
                                                  │
                                                  ▼
                                          [DiscoveryStage]
                                                  │
                                                  ▼
                                          SchemaDiscovered
                                                  │
                                                  ▼
                                          [ProfilingStage]
                                                  │
                                                  ▼
                                          DataProfiled
                                                  │
                                                  ▼
                                          [AlignmentStage]
                                                  │
                                                  ▼
                                          (null) → run COMPLETED
```

### 7.3 阶段输入输出

| Stage | 输入 event | 输出 event | 复用服务 | 写入 result_type |
|---|---|---|---|---|
| PipelineIngestionStage | IngestionRequested | RawDataIngested | DuckDbSyncService（per table） | raw_sync_report |
| PipelineDiscoveryStage | RawDataIngested | SchemaDiscovered | DiscoveryService | discovery_report |
| PipelineProfilingStage | SchemaDiscovered | DataProfiled | ProfilingService | profile_report |
| PipelineAlignmentStage | DataProfiled | SemanticAligned（终止事件） | AlignmentService | alignment_map |

**Per-table iteration**：ProfilingStage 接收 `SchemaDiscovered.discoveredTableFqns`，对每个 tableFQN 调用 `ProfilingService.profile(tableFQN)`。部分成功策略：若 ≥1 个 table 失败：报告 `partialFailure=true`，若所有 table 都失败：抛 `RecoverableFailure`。

阶段 5-8 通过新增 event type + 注册新 stage 接入。

---

## 8. REST API（Spring MVC）

### 8.1 端点

| Method | Path | 用途 | Phase 7a |
|---|---|---|---|
| POST | `/v1/pipeline/runs` | 触发管线 | ✅ |
| GET | `/v1/pipeline/runs/{runUuid}` | 查询 run + 阶段状态 | ✅ |
| GET | `/v1/pipeline/runs` | 列出 runs | ✅ |
| GET | `/v1/pipeline/dead-letter` | 列出死信 | ✅ |
| POST | `/v1/pipeline/dead-letter/{id}/replay` | 重放死信 | ❌ |
| POST | `/v1/pipeline/runs/{runUuid}/cancel` | 取消 run | ❌ |

### 8.2 POST /v1/pipeline/runs

请求：
```json
{
  "sourceFqn": "prod.pg.customers_db",
  "tableFqns": ["prod.pg.customers_db.public.customers", "prod.pg.customers_db.public.orders"],
  "triggerType": "MANUAL"
}
```

注：**不包含 connector config**（避免 credentials 进 payload）。连接配置通过 sourceFqn 在 `SourceRegistry` 中查找（Phase 6 已有 source 概念）。

响应（202 Accepted，header `Location: /v1/pipeline/runs/{runUuid}`）：
```json
{
  "runUuid": "5d4e2c1a-...",
  "status": "PENDING",
  "triggerType": "MANUAL",
  "correlationId": "...",
  "createdAt": "2026-07-16T22:00:00Z"
}
```

错误：
- 400：参数缺失
- 409：同 sourceFqn 已有 active run
- 500：内部错误

### 8.3 GET /v1/pipeline/runs/{runUuid}

```json
{
  "runUuid": "...",
  "tenantId": "default",
  "sourceFqn": "prod.pg.customers_db",
  "status": "RUNNING",
  "triggerType": "MANUAL",
  "correlationId": "...",
  "stages": [
    { "stageName": "ingestion", "status": "COMPLETED", "attempts": 1,
      "startedAt": "...", "completedAt": "..." },
    { "stageName": "discovery", "status": "COMPLETED", "attempts": 1,
      "startedAt": "...", "completedAt": "..." },
    { "stageName": "profiling", "status": "RUNNING", "attempts": 1,
      "startedAt": "..." },
    { "stageName": "alignment", "status": "PENDING", "attempts": 0 }
  ],
  "createdAt": "...",
  "completedAt": null
}
```

### 8.4 GET /v1/pipeline/runs

查询参数：`sourceFqn`、`status`、`limit`（默认 50，最大 500）、`offset`（默认 0）。

### 8.5 GET /v1/pipeline/dead-letter

查询参数：`sourceFqn`、`replayed`（true/false/不传）、`limit`、`offset`。

---

## 9. 向后兼容迁移

### 9.1 DiscoveryResource 变更

**修改前**（Spring MVC）：
```java
@PostMapping("/v1/discovery/sources/{sourceFQN}/run")
public DiscoveryReport trigger(@PathVariable String sourceFQN,
                                @RequestParam(defaultValue = "true") boolean profile) {
    return discoveryService.run(sourceFQN, profile);
}
```

**修改后：**
```java
@PostMapping("/v1/discovery/sources/{sourceFQN}/run")
public ResponseEntity<PipelineRunResponse> trigger(@PathVariable String sourceFQN,
                                                     @RequestParam(defaultValue = "true") boolean profile) {
    // 提取该 source 已知 tableFQN 列表（通过 SourceRegistry 或上一次 discovery）
    List<String> tableFqns = sourceRegistry.listTables(sourceFQN);
    var run = pipelineService.start(sourceFQN, tableFqns, PipelineTriggerType.DISCOVERY_AUTO);
    return ResponseEntity
        .status(HttpStatus.ACCEPTED)
        .header("Location", "/v1/pipeline/runs/" + run.runUuid())
        .body(run);
}
```

### 9.2 兼容性策略

- API 路径**不变**：`/v1/discovery/sources/{sourceFQN}/run`
- 响应类型 `DiscoveryReport` → `PipelineRunResponse`（破坏性变更）
- 现有客户端：拿 `runUuid` → 轮询 `GET /v1/pipeline/runs/{runUuid}`
- **保留旧的同步行为为可选 alias**：`@PostMapping("/v1/discovery/sources/{sourceFQN}/run-sync")` 返回同步 report，标记 deprecated。后续 v2 移除。

---

## 10. 测试策略

| 层 | 测试类型 | 范围 |
|---|---|---|
| Unit | JUnit 5 + Mockito | InProcessBus.publish 事务行为、OutboxProcessor 派发逻辑、StageRegistry |
| Unit | JUnit 5 | 每个 Stage 适配器（mock 复用服务 + 验证 result 写入） |
| Unit | JUnit 5 | PipelineFailure 分类（Recoverable / Fatal） |
| Integration | Testcontainers | 端到端：POST → 202 → outbox 派发 → 阶段流转 → COMPLETED |
| Integration | Testcontainers | per-stage 重试：模拟 RecoverableFailure → RETRYING → 重派发 → COMPLETED |
| Integration | Testcontainers | DLQ：模拟 FatalFailure → DEAD_LETTER |
| Integration | Testcontainers | 崩溃恢复：注入 CLAIMED 记录 + claimed_until 超时 → 重声明 |
| Integration | Testcontainers | 并发：多线程 POST 同 source → 一个 202 一个 409 |
| Integration | Testcontainers | DiscoveryResource 自动触发管线（DISCOVERY_AUTO） |
| Integration | Testcontainers | 旧 run-sync alias 仍返回同步 report |

---

## 11. 任务清单

```
Phase 7a — Pipeline 骨架 + 前 4 阶段
├── 7a.0  heirloom-core 接口（EventBus / Event / Stage / Run / StageStatus / Status / Context / Failure / Registry）
├── 7a.1  PipelineRunEntity + PipelineStageStatusEntity + PipelineResultEntity + PipelineOutboxEntity + DeadLetterEntity + JPA Repositories
├── 7a.2  Flyway V22-V27
├── 7a.3  InProcessBus 实现（publish → outbox 写入）
├── 7a.4  OutboxProcessor（@Scheduled 拉取 + SELECT FOR UPDATE SKIP LOCKED）
├── 7a.5  PipelineStageRegistry 实现
├── 7a.6  PipelineOrchestrator（@PostConstruct 注册 4 阶段）
├── 7a.7  PipelineIngestionStage（per-table DuckDbSyncService + 幂等检查）
├── 7a.8  PipelineDiscoveryStage（包装 DiscoveryService + SourceRegistry 查询 + result 写入）
├── 7a.9  PipelineProfilingStage（per-table 迭代 + partial-failure 处理 + result 写入）
├── 7a.10 PipelineAlignmentStage（包装 AlignmentService + result 写入）
├── 7a.10b PipelineOrchestrator 装配 4 阶段 + SemanticAligned 终止 stage
├── 7a.11 PipelineService（start / get / list）
├── 7a.12 PipelineResource REST endpoints（runs + dead-letter 列表）
├── 7a.13 DiscoveryResource 改为触发管线（保留 /run-sync deprecated alias）
├── 7a.14 单元测试（bus / processor / registry / 每个 stage / orchestrator）
├── 7a.15 集成测试（Testcontainers：端到端、重试、DLQ、崩溃恢复、并发、自动触发）
└── 7a.16 验证 + 文档
```

---

## 12. 安全说明

- **Credentials 不进 payload**：连接配置通过 `sourceFqn` 在 `SourceRegistry` 查找，run payload 只存 `tableFqns` 与 run 元数据
- **API 响应不返回 secret**：所有 endpoint DTO 不含连接配置
- **日志不打印 payload**：structured log 只打印 eventId / runUuid / stage / status

---

## 13. 相关 ADR

- [ADR-038](../../adr/038-raw-to-ontology-pipeline.md) — raw → ontology 管线（架构来源）
- [ADR-039](../../adr/039-kafka-pipeline-topics.md) — Kafka topic 组织（Phase 7b+）
- [ADR-042](../../adr/042-multi-tenant-deployment.md) — 多租户（字段已预留）
- [ADR-046](../../adr/046-metadata-first-mvp.md) — 元数据优先 MVP（Phase 6 完成）