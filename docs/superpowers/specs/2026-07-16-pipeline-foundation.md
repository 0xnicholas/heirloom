# Heirloom Pipeline 基础建设 — 设计规格

**日期**: 2026-07-16
**状态**: Draft
**参考**: ADR-038、ADR-039、ADR-046
**范围**: Phase 7a（前 4 阶段骨架）

---

## 1. 背景

Phase 5 引入 DuckDB raw store + Query Router，Phase 6 补齐 metadata catalog（classifications / tags / domain / column profiles / alignment）。但当前架构存在明显断层：

- **raw 数据** 在 DuckDB 里
- **metadata catalog** 已有能力（Table / Column / Classification / Tag）
- **ontology** 仍为空

raw table 如何映射到 ontology 实体？目前完全手动。ADR-038 提出 8 阶段事件驱动管线（Ingestion → Discovery → Profiling → Alignment → Entity Resolution → Ontology Proposal → Governance → Mapping & Publish），但全量实施成本过高、依赖 Kafka 基础设施。

Phase 7a 在 **不引入 Kafka** 的前提下，建立**可插拔管线骨架**并实施前 4 阶段，将 Phase 5/6 已有能力（Discovery / Profiling / Alignment）接入管线，验证端到端自动化闭环。后续阶段（5-8）与 Kafka adapter 留 Phase 7b/8。

### 1.1 与 ADR-038 的关系

ADR-038 已确定管线架构（事件驱动、Kafka 主题、DLQ）。Phase 7a 在以下方面与 ADR 一致：
- 8 阶段顺序与事件命名
- DLQ 失败隔离策略
- Event Log 与管线事件的职责分工

差异：
- **不引入 Kafka**：用 `PipelineEventBus` 接口 + in-process 实现（同步派发 + 事务边界）
- **只实施前 4 阶段**：Ingestion / Discovery / Profiling / Alignment
- **接口预留 `tenantId`**：默认 `"default"`，待 ADR-042 多租户隔离时填字段

---

## 2. 设计目标

### 2.1 Phase 7a 必达

- 4 阶段管线骨架：Ingestion → Discovery → Profiling → Alignment
- 可插拔 `PipelineEventBus` 接口（heirloom-core）+ in-process 实现
- DB-backed 重试 + DLQ（进程重启不丢消息）
- 现有 `DiscoveryResource.trigger()` 改为触发管线，外部 API 语义变更（同步报告 → 异步 run）
- 完整 REST API：`/api/v1/pipeline/runs`、`/dead-letter`
- 单元 + Testcontainers 集成测试覆盖

### 2.2 非目标（Phase 7a 不做）

- 阶段 5-8（Entity Resolution / Ontology Proposal / Governance / Mapping & Publish）
- Kafka adapter（接口已就位，实现留 Phase 7b 或 8）
- 调度器触发（@Scheduled 拉数据源）
- CDC 事件触发
- DLQ 重放 UI
- 多租户隔离（字段已预留）
- 取消 RUNNING run

---

## 3. 架构与模块布局

### 3.1 接口模块（heirloom-core）

零 Spring 依赖，与 Phase 6 模式一致。

```
heirloom-core/src/main/java/com/heirloom/core/pipeline/
├── PipelineEventBus.java          # publish / subscribe / recoverPending
├── PipelineEvent.java             # sealed interface
├── PipelineEventType.java         # enum
├── PipelineStage.java             # @FunctionalInterface（输入事件 → 输出事件）
├── PipelineStageRegistry.java     # 注册表（按 eventType 索引）
├── PipelineRun.java               # entity interface
├── PipelineStatus.java            # enum (PENDING | RUNNING | COMPLETED | FAILED | RETRYING | DEAD_LETTER)
├── PipelineTriggerType.java       # enum (MANUAL | DISCOVERY_AUTO)
├── PipelineContext.java           # record（tenantId/sourceId/correlationId/...）
└── PipelineFailure.java           # sealed (RecoverableFailure | FatalFailure)
```

### 3.2 实现模块（heirloom-server）

Spring 装配、JPA 持久化、REST 端点。

```
heirloom-server/src/main/java/com/heirloom/pipeline/
├── bus/
│   ├── InProcessEventBus.java          # 同步派发 + 事务边界 + 异常分类
│   └── PipelineEventPublisher.java     # 外部触发入口（包装 publish）
├── stages/
│   ├── IngestionStage.java             # 包装 DuckDbSyncService
│   ├── DiscoveryStage.java             # 包装 DiscoveryService
│   ├── ProfilingStage.java             # 包装 ProfilingService
│   └── AlignmentStage.java             # 包装 AlignmentService
├── orchestrator/
│   └── PipelineOrchestrator.java       # @PostConstruct 装配 4 阶段订阅
├── persistence/
│   ├── PipelineRunEntity.java          # JPA
│   ├── PipelineRunJpaRepository.java
│   ├── PipelineRunRepository.java
│   ├── DeadLetterEntity.java           # JPA
│   └── DeadLetterJpaRepository.java
├── retry/
│   └── RetryScheduler.java             # @Scheduled 扫描 RETRYING 重入队
├── service/
│   └── PipelineService.java            # start / get / list
└── web/
    ├── PipelineResource.java           # REST: runs + dead-letter
    └── dto/
        ├── TriggerPipelineRequest.java
        ├── PipelineRunResponse.java
        ├── PipelineStageStatusDto.java
        └── DeadLetterResponse.java
```

### 3.3 Flyway 迁移

```
heirloom-server/src/main/resources/db/migration/
├── V22__create_pipeline_runs.sql
└── V23__create_pipeline_dead_letter.sql
```

---

## 4. 核心接口契约

### 4.1 PipelineEventBus

```java
public interface PipelineEventBus {
    /** 同步发布事件，调用所有 subscribers；任一抛异常则整体失败（事务回滚） */
    void publish(PipelineEvent event);

    /** 注册订阅者（按 event type 路由） */
    void subscribe(PipelineEventType type, PipelineStage subscriber);

    /** 启动时从 DB 加载 RETRYING 的 run 重新入队 */
    void recoverPending();
}
```

### 4.2 PipelineEvent（sealed interface）

```java
public sealed interface PipelineEvent
    permits IngestionRequested, RawDataIngested, SchemaDiscovered,
            DataProfiled, SemanticAligned {

    UUID runUuid();
    String tenantId();
    String sourceId();
    String correlationId();
    PipelineEventType type();
    Instant occurredAt();
    int attempts();
}
```

每个事件是 record：

```java
public record IngestionRequested(
    String sourceFqn, String connectorType, JsonNode config,
    boolean profile, boolean align,
    UUID runUuid, String tenantId, String sourceId,
    String correlationId, Instant occurredAt, int attempts
) implements PipelineEvent { ... }

public record RawDataIngested(
    List<String> ingestedTableFqns, Instant syncedAt,
    UUID runUuid, String tenantId, String sourceId,
    String correlationId, Instant occurredAt, int attempts
) implements PipelineEvent { ... }

public record SchemaDiscovered(
    List<String> discoveredTableFqns, int tableCount,
    UUID runUuid, String tenantId, String sourceId,
    String correlationId, Instant occurredAt, int attempts
) implements PipelineEvent { ... }

public record DataProfiled(
    List<String> profiledTableFqns, int profiledCount, double avgQualityScore,
    UUID runUuid, String tenantId, String sourceId,
    String correlationId, Instant occurredAt, int attempts
) implements PipelineEvent { ... }

public record SemanticAligned(
    List<String> alignedResourceFqns, int alignedCount, int newSuggestions,
    UUID runUuid, String tenantId, String sourceId,
    String correlationId, Instant occurredAt, int attempts
) implements PipelineEvent { ... }
```

### 4.3 PipelineStage

```java
@FunctionalInterface
public interface PipelineStage {
    /** 处理输入事件，返回输出的下一个事件（可能为 null 表示终止） */
    PipelineEvent apply(PipelineEvent input, PipelineContext context)
        throws PipelineFailure;
}
```

### 4.4 PipelineFailure

```java
public sealed interface PipelineFailure extends RuntimeException
    permits RecoverableFailure, FatalFailure {

    String message();  // sealed interface 要求兼容 Exception

    record RecoverableFailure(String message, Throwable cause)
        implements PipelineFailure {}

    record FatalFailure(String message, Throwable cause)
        implements PipelineFailure {}
}
```

注：若 sealed interface 与 Exception 兼容性受 Java 21 限制，改为 abstract class。

### 4.5 PipelineRun（entity interface）

```java
public interface PipelineRun {
    UUID getRunUuid();
    String getTenantId();
    String getSourceId();
    String getCurrentStage();
    PipelineStatus getStatus();
    String getCorrelationId();
    PipelineTriggerType getTriggerType();
    int getAttempts();
    int getMaxAttempts();
    String getLastError();
    Instant getNextRetryAt();
    Instant getCreatedAt();
    Instant getUpdatedAt();
    Instant getCompletedAt();
}
```

---

## 5. 数据模型

### 5.1 Flyway V22 — pipeline_runs

```sql
CREATE TABLE pipeline_runs (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL UNIQUE,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  source_id VARCHAR(255) NOT NULL,
  current_stage VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  correlation_id UUID NOT NULL,
  trigger_type VARCHAR(32) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  last_error TEXT,
  payload JSONB,
  result JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  next_retry_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ
);

CREATE INDEX idx_pipeline_runs_status_retry
  ON pipeline_runs (status, next_retry_at)
  WHERE status = 'RETRYING';

CREATE INDEX idx_pipeline_runs_source
  ON pipeline_runs (tenant_id, source_id);
```

### 5.2 Flyway V23 — pipeline_dead_letter

```sql
CREATE TABLE pipeline_dead_letter (
  id BIGSERIAL PRIMARY KEY,
  run_uuid UUID NOT NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
  source_id VARCHAR(255) NOT NULL,
  stage VARCHAR(64) NOT NULL,
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

### 5.3 状态机

```
              trigger
                │
                ▼
            ┌────────┐
            │ PENDING │ ◄──┐
            └────┬───┘    │ retry (attempts < max)
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
                         │ attempts ≥ max_attempts
                         ▼
                  ┌─────────────┐
                  │ DEAD_LETTER │
                  └─────────────┘
```

---

## 6. 事件总线实现（InProcessEventBus）

### 6.1 publish 行为

```
publish(event):
  1. 在当前事务边界内执行：
     a. 查找 PipelineRun by runUuid
     b. 校验 status ∈ {PENDING, RUNNING, RETRYING}
     c. 更新 status = RUNNING, currentStage = event.type(), attempts++
  2. 调用所有 subscribers（按注册顺序）：
     - 每个 subscriber 在同一事务内执行
     - 收集返回的 nextEvent（第一个非 null 的）
  3. 若 nextEvent != null：
     - 递归 publish(nextEvent)
  4. 若所有 stage 链完成且无 nextEvent：
     - status = COMPLETED, completedAt = now()
  5. 任一 subscriber 抛 PipelineFailure：
     a. 分类（RecoverableFailure vs FatalFailure）
     b. attempts < max_attempts 且可恢复：
        → status = RETRYING
        → nextRetryAt = now() + min(2^attempts * 10s, 5min)
        → lastError = failure.message()
     c. 否则：
        → 写入 pipeline_dead_letter
        → status = DEAD_LETTER
     d. 抛 PipelineStageFailedException 给 caller（REST 返回 500）
```

### 6.2 recoverPending 行为

```
recoverPending():
  1. 查找 status = RETRYING 且 nextRetryAt <= now() 的所有 run
  2. 对每个 run：从 run.payload 反序列化原始 IngestionRequested 事件
  3. 重新 publish（attempts 已递增）
```

由 `RetryScheduler` 定时调用（`@Scheduled(fixedDelayString = "${heirloom.pipeline.retry-poll-seconds:30}")`）。

### 6.3 异常分类

| 异常类型 | 重试策略 |
|---|---|
| `RecoverableFailure` | 重试，最多 `max_attempts` 次 |
| `FatalFailure` | 直接 DLQ，不重试 |
| 其他 `RuntimeException` | 默认视为 fatal，写入 DLQ |

### 6.4 幂等性要求

每个 stage 必须**幂等**（同 payload 多次执行结果一致）：

| Stage | 幂等机制 |
|---|---|
| IngestionStage | DuckDB `CREATE TABLE IF NOT EXISTS` + 原子 rename（Phase 5 已实现） |
| DiscoveryStage | `tableFQN` upsert TableEntity（已存在则更新） |
| ProfilingStage | 检查 `tableProfile.profiledAt` 是否新鲜（TTL 内跳过） |
| AlignmentStage | 用 `proposedResource` 的 hash 去重 |

---

## 7. 阶段编排

### 7.1 PipelineOrchestrator

不直接调用 stages，而是**订阅事件并发布下一个事件**：

```java
@Component
public class PipelineOrchestrator {
    @PostConstruct void wire() {
        bus.subscribe(IngestionRequested, ingestionStage);
        bus.subscribe(RawDataIngested, discoveryStage);
        bus.subscribe(SchemaDiscovered, profilingStage);
        bus.subscribe(DataProfiled, alignmentStage);
    }
}
```

### 7.2 事件链（Phase 7a）

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
                                          SemanticAligned   ← Phase 7a 终点
```

阶段 5-8（Entity Resolution / Ontology Proposal / Governance / Mapping & Publish）通过新增 stage + 新事件类型接入，不修改现有 stage。

### 7.3 阶段输入输出

| Stage | 输入事件 | 输出事件 | 复用服务 |
|---|---|---|---|
| IngestionStage | IngestionRequested | RawDataIngested | DuckDbSyncService |
| DiscoveryStage | RawDataIngested | SchemaDiscovered | DiscoveryService |
| ProfilingStage | SchemaDiscovered | DataProfiled | ProfilingService |
| AlignmentStage | DataProfiled | SemanticAligned | AlignmentService |

---

## 8. REST API

### 8.1 端点清单

| Method | Path | 用途 | Phase 7a |
|---|---|---|---|
| POST | `/api/v1/pipeline/runs` | 手动触发管线 | ✅ |
| GET | `/api/v1/pipeline/runs/{runUuid}` | 查询 run 状态 | ✅ |
| GET | `/api/v1/pipeline/runs` | 列出 runs | ✅ |
| GET | `/api/v1/pipeline/dead-letter` | 列出死信 | ✅ |
| POST | `/api/v1/pipeline/dead-letter/{id}/replay` | 重放死信 | ❌ 后续 |
| POST | `/api/v1/pipeline/runs/{runUuid}/cancel` | 取消 run | ❌ 后续 |

### 8.2 POST /api/v1/pipeline/runs

请求：
```json
{
  "sourceId": "prod.pg.customers_db",
  "connectorType": "postgres",
  "config": { "host": "pg.internal", "port": 5432, "database": "customers", ... },
  "stages": ["ingestion", "discovery", "profiling", "alignment"]
}
```

响应（202 Accepted）：
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
- 409：同 source 已有 RUNNING run
- 500：触发失败（pipeline 内错误）

### 8.3 GET /api/v1/pipeline/runs/{runUuid}

```json
{
  "runUuid": "5d4e2c1a-...",
  "tenantId": "default",
  "sourceId": "prod.pg.customers_db",
  "status": "RUNNING",
  "currentStage": "profiling",
  "attempts": 1,
  "stages": [
    { "name": "ingestion", "status": "COMPLETED", "completedAt": "..." },
    { "name": "discovery", "status": "COMPLETED", "completedAt": "..." },
    { "name": "profiling", "status": "RUNNING", "startedAt": "..." },
    { "name": "alignment", "status": "PENDING" }
  ],
  "lastError": null,
  "createdAt": "...",
  "completedAt": null
}
```

### 8.4 GET /api/v1/pipeline/runs

查询参数：
- `sourceId`（可选）
- `status`（可选，逗号分隔）
- `limit`（默认 50）
- `offset`（默认 0）

### 8.5 GET /api/v1/pipeline/dead-letter

查询参数：
- `replayed`（可选：true / false / 不传=全部）
- `limit`（默认 50）
- `offset`（默认 0）

---

## 9. 向后兼容迁移

### 9.1 DiscoveryResource 变更

**修改前：**
```java
@POST @Path("/sources/{sourceId}/discover")
public DiscoveryReport triggerDiscovery(
    @PathParam String sourceId,
    @QueryParam("profile") boolean profile
) {
    return discoveryService.run(sourceId, profile);  // 同步阻塞
}
```

**修改后：**
```java
@POST @Path("/sources/{sourceId}/discover")
public PipelineRunResponse triggerDiscovery(
    @PathParam String sourceId,
    @QueryParam("profile") boolean profile
) {
    var run = pipelineService.startForSource(sourceId, "DISCOVERY_AUTO");
    return run;  // 立即返回 PENDING
}
```

### 9.2 兼容性策略

- API 路径不变（`/api/v1/discovery/sources/{id}/discover`）
- 响应类型从 `DiscoveryReport` → `PipelineRunResponse`（破坏性变更）
- 现有客户端需要：拿 `runUuid` → 轮询 `GET /api/v1/pipeline/runs/{runUuid}`
- 旧同步路径**保留为 deprecated 别名**（返回 301 + Location 头指向新端点），v2 移除

### 9.3 DiscoveryService 不变

`DiscoveryService` 仍存在，但现在只被 `DiscoveryStage` 调用。其他直接调用方需改为触发管线。

---

## 10. 测试策略

| 层 | 测试类型 | 范围 |
|---|---|---|
| Unit | JUnit 5 + Mockito | InProcessEventBus publish/subscribe/recover、状态机转换、RetryScheduler、异常分类 |
| Unit | JUnit 5 | 每个 Stage 的输入输出映射（mock 复用服务） |
| Integration | `@SpringBootTest` + Testcontainers | 端到端：POST → PENDING → RUNNING → COMPLETED |
| Integration | Testcontainers | DB-backed 重试：模拟 RecoverableFailure → RETRYING → 重入队 → COMPLETED |
| Integration | Testcontainers | DLQ：模拟 FatalFailure → DEAD_LETTER 记录 |
| Integration | Testcontainers | DiscoveryResource 自动触发管线 |
| Integration | Testcontainers | 并发：同 source 多个 run |

---

## 11. 任务清单（写到 plan 时细化）

```
Phase 7a — Pipeline 骨架 + 前 4 阶段
├── 7a.0  heirloom-core 定义 Pipeline 接口（EventBus / Event / Stage / Run / Status / Context / Failure）
├── 7a.1  PipelineRunEntity + DeadLetterEntity + JPA Repository + Flyway V22/V23
├── 7a.2  InProcessEventBus 实现（同步派发 + 事务边界 + 异常分类）
├── 7a.3  RetryScheduler（@Scheduled 扫描 RETRYING 重入队）
├── 7a.4  IngestionStage（包装 DuckDbSyncService）
├── 7a.5  DiscoveryStage（包装 DiscoveryService）
├── 7a.6  ProfilingStage（包装 ProfilingService）
├── 7a.7  AlignmentStage（包装 AlignmentService）
├── 7a.8  PipelineOrchestrator（@PostConstruct 装配 4 阶段订阅）
├── 7a.9  PipelineService（start / get / list）
├── 7a.10 PipelineResource REST endpoints（runs + dead-letter 列表）
├── 7a.11 DiscoveryResource 改为触发管线（保留旧路径 deprecated alias）
├── 7a.12 单元测试（bus / scheduler / 每个 stage / orchestrator）
├── 7a.13 集成测试（Testcontainers 端到端、重试、DLQ、自动触发、并发）
└── 7a.14 验证 + 文档
```

---

## 12. 相关 ADR

- [ADR-038](./../adr/038-raw-to-ontology-pipeline.md) — raw → ontology 管线（架构来源）
- [ADR-039](./../adr/039-kafka-pipeline-topics.md) — Kafka topic 组织（Phase 7b+）
- [ADR-042](./../adr/042-multi-tenant-deployment.md) — 多租户（字段已预留）
- [ADR-046](./../adr/046-metadata-first-mvp.md) — 元数据优先 MVP（Phase 6 完成，本 Phase 衔接）
