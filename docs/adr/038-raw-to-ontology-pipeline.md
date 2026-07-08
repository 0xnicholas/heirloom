# ADR-038: raw 数据到业务本体语义的事件驱动管线

## 状态

Proposed

## 日期

2026-07-08

## 上下文

Heirloom 需要把客户系统 raw 数据逐步转化为业务本体语义（Resource Type、Relationship、Role 等）。这不是一个单一操作，而是一个多阶段过程：

1. raw 数据接入
2. schema 发现
3. 数据剖析（profiling）
4. 语义对齐
5. 实体解析
6. 本体提案
7. 治理审批
8. 映射与发布

这些阶段之间需要松耦合、可观测、可重试。如果硬编码为线性同步调用，某阶段失败会导致全流程阻塞，且新增阶段时需要修改上下游代码。

## 决策

**采用事件驱动管线（Event-Driven Pipeline）编排 raw 数据到业务本体语义的转换过程。**

### 事件总线

使用 **Kafka** 作为事件总线，统一 Topic：`heirloom.pipeline.events`，按 `tenant + source` 分区，保证同一租户/数据源内的事件顺序。

### 与 ADR-036 Discovery Connector 的关系

- **Discovery Connector**（如 `PostgresSchemaExtractor`）是**数据源接入的实现插件**，定义在 `heirloom-core` SPI 中，由 `heirloom-connector-postgres` 实现。
- **Discovery Stage** 是 raw→ontology 管线中的**编排步骤**，它调用 Connector 从 DuckDB 提取 schema，然后创建 `TableEntity` 和 `LineageEntity`。
- 简言之：Connector 是“怎么读”，Discovery Stage 是“什么时候读、读完做什么”。

### 管线阶段与事件

| 阶段 | 输入事件 | 输出事件 | 主要职责 |
|------|---------|---------|---------|
| Ingestion | `IngestionRequested` | `RawDataIngested` | 把 raw 数据同步到租户 DuckDB |
| Discovery | `RawDataIngested` | `SchemaDiscovered` | 从 DuckDB 提取 schema、血缘 |
| Profiling | `SchemaDiscovered` | `DataProfiled` | 统计列级指标、质量评分 |
| Alignment | `DataProfiled` | `SemanticAligned` | raw 列 → Resource 字段对齐 |
| Entity Resolution | `SemanticAligned` | `EntitiesResolved` | 跨源同一实体识别与 RID 映射 |
| Ontology Proposal | `EntitiesResolved` | `OntologyProposed` | 生成 Resource Type 提案 |
| Governance | `OntologyProposed` | `ProposalApproved` / `ProposalRejected` | 人工审批 |
| Mapping & Publish | `ProposalApproved` | `OntologyPublished` | 创建 MappingRule，发布到 Schema Registry |

### 事件 Schema 公共头

```json
{
  "eventId": "uuid",
  "eventType": "RawDataIngested",
  "tenantId": "acme-corp",
  "sourceId": "prod.pg.customers_db",
  "timestamp": "2026-07-08T08:00:00Z",
  "correlationId": "pipeline-run-uuid",
  "payload": { }
}
```

### 触发机制

`IngestionRequested` 事件可由以下方式触发：

| 触发源 | 场景 | 本阶段支持 |
|--------|------|-----------|
| 用户手动调用 API | 数据分析师想分析某数据源 | ✅ 优先支持 |
| 定时调度任务 | 每日/每小时自动同步核心表 | ⚠️ 后续支持 |
| CDC 事件 | 源系统数据变更时自动触发增量同步 | ⚠️ 后续支持 |
| Discovery 完成后自动触发 | 发现新表后自动接入分析 | ⚠️ 可配置 |

### 与 Heirloom Event Log 的关系

- **管线事件**（`RawDataIngested`、`SchemaDiscovered` 等）走 Kafka topic `heirloom.pipeline.events`，用于阶段间编排。
- **业务审计事件**（创建 `TableEntity`、审批 `Proposal`、发布 `ResourceType`）仍走现有 `ChangeEventInterceptor` 机制，写入 `change_events` 表。
- 两者不重复：管线事件是“阶段流转”，Event Log 是“业务变更审计”。

### 失败处理与重试

| 失败类型 | 处理策略 |
|---------|---------|
| 可恢复错误（网络超时、源系统暂时不可用） | 指数退避重试 3 次，失败后进入 DLQ |
| 不可恢复错误（配置错误、权限不足） | 直接写入 DLQ，发布 `StageFailed` 事件 |
| 人工拒绝提案 | 发布 `ProposalRejected`，管线终止，不进入 DLQ |
| 死信队列（DLQ） | `heirloom.pipeline.dlq`，供运维人工排查和重放 |

### 关键设计原则

1. **领域事件式命名**：事件使用过去时（`RawDataIngested`），表达“已经发生了什么”。
2. **阶段自治**：每个阶段是独立消费者，只订阅自己关心的 event type。
3. **失败隔离**：单个阶段失败不影响其他已处理阶段，失败事件进入重试或 DLQ。
4. **审计天然**：每个阶段的业务写入自动进入 Heirloom Event Log，管线事件本身也保留在 Kafka 中可追溯。

## 后果

**积极**：
- 阶段之间松耦合，新增/替换阶段不需要修改上游。
- 便于扩展： profiling、alignment、entity resolution 可以独立演进。
- 支持异步人工审批， governance 成为管线自然一环。
- Kafka 持久化保证事件不丢失，支持断点续传和重放。

**消极**：
- 引入 Kafka 基础设施，增加运维复杂度。
- 最终一致性：阶段之间存在延迟，需要明确 freshness 预期。
- 调试链路变长：一个问题可能跨多个消费者，需要 correlation id 和分布式追踪。
- 事件 schema 演进需要版本管理。

## 备选方案

### 方案 A：线性同步管线

所有阶段顺序执行，一个失败则整体回滚。

**放弃理由**：阶段之间紧耦合，新增阶段困难；人工审批无法嵌入同步流程；源系统压力集中。

### 方案 B：DAG 工作流引擎

使用 Airflow / Temporal / 自研 DAG 引擎编排阶段。

**放弃理由**：对于当前阶段的复杂度有些过重。Kafka + 事件消费者已能满足需求，且与 Heirloom 现有事件驱动架构（Event Log）更一致。未来若阶段依赖变复杂，可再引入 DAG 引擎。

### 方案 C：Spring ApplicationEvent + 内存队列

使用 Spring 内置事件机制。

**放弃理由**：重启丢失、不支持跨进程、无法持久化和重放。不适合跨阶段的长周期管线（尤其是包含人工审批）。

## 相关 ADR

- [ADR-001](./001-semantic-core-as-hub.md) — 语义中枢
- [ADR-003](./003-storage-separation.md) — 存储层分离（Event Log）
- [ADR-019](./019-two-phase-discovery.md) — 两阶段发现
- [ADR-023](./023-inference-pipeline.md) — InferencePipeline
- [ADR-036](./036-semantic-layer-modularization.md) — Semantic Layer 模块化
- [ADR-037](./037-duckdb-raw-store.md) — DuckDB raw store
- [ADR-039](./039-kafka-pipeline-topics.md) — Kafka topic 组织策略
