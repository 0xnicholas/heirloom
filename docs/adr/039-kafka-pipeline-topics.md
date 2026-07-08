# ADR-039: 管线事件 Kafka Topic 组织策略

## 状态

Proposed

## 日期

2026-07-08

## 上下文

在 ADR-038 中，我们决定使用 Kafka 编排 raw 到 ontology 的事件驱动管线。接下来需要决定 Kafka Topic 的组织方式：

- 所有阶段事件共用一个 topic，还是每个阶段独立 topic？
- 如何分区以保证事件顺序和可扩展性？
- 如何控制 consumer group 数量？

Topic 组织方式直接影响运维复杂度、消费延迟、调试难度和未来的扩展能力。

## 决策

**采用“统一 Topic + 按 tenant/source 分区”的策略。**

### 具体设计

- **Topic**：`heirloom.pipeline.events`
- **分区键**：`tenantId + sourceId` 的组合哈希
- **消费者**：每个阶段一个独立 consumer group，按 `eventType` 过滤处理自己关心的事件
- **消息格式**：CloudEvents 风格，公共头包含 `eventType`、`tenantId`、`sourceId`、`correlationId`

### 分区理由

| 目标 | 分区策略效果 |
|------|-------------|
| 同一租户/数据源内的事件顺序 | `tenantId + sourceId` 固定映射到同一分区，保证顺序 |
| 租户隔离 | 不同租户天然分散到不同分区，避免互相阻塞 |
| 水平扩展 | 分区数可随租户增长而扩展，consumer group 可水平扩容 |
| 运维简单 | 只需管理一个 topic，监控、备份、权限控制更集中 |

### 事件 Schema 版本管理

统一 topic 下 event type 会随阶段演进，需要 schema 版本控制：

1. **公共头稳定**：`eventId`、`eventType`、`tenantId`、`sourceId`、`timestamp`、`correlationId`、`payloadVersion` 等字段不变。
2. **payload 版本化**：每个 event type 的 payload 带有 `payloadVersion` 字段。
3. **向后兼容**：新版本 consumer 必须能读取旧版本 payload；旧版本 consumer 忽略不识别的新字段。
4. **Schema 注册**：使用 Confluent Schema Registry 或自研 JSON Schema 注册表管理 event payload schema。
5. **破坏性变更**：需要升级 `payloadVersion` _major 版本时，创建新的 event type（如 `RawDataIngestedV2`）或新的 topic，避免同一 topic 内出现不兼容消息。

### 消费者过滤

每个阶段 consumer 订阅统一 topic，在消费端按 `eventType` 过滤：

```java
@KafkaListener(topics = "heirloom.pipeline.events", groupId = "discovery-service")
public void handle(ConsumerRecord<String, PipelineEvent> record) {
    if (!"RawDataIngested".equals(record.value().getEventType())) return;
    // process
}
```

## 后果

**积极**：
- 单一 topic 简化运维和 schema 管理。
- 按租户分区保证顺序，同时支持扩展。
- 新增阶段只需新增 consumer group，无需创建新 topic。
- 便于全局监控管线吞吐量和延迟。

**消极**：
- 所有阶段消费者共享分区，某个阶段处理慢可能拖慢同分区其他事件的消费（但不同 consumer group 互不影响）。
- 如果某个阶段需要特别高的并发，无法单独为其 topic 调优。
- 事件类型多后，单一 topic 的消息量变大，需要合理设置 retention 和 compaction。

## 备选方案

### 方案 A：每阶段一个 topic

如 `heirloom.pipeline.ingestion`、`heirloom.pipeline.discovery` 等。

**放弃理由**：topic 数量随阶段线性增长，运维和权限管理复杂。阶段之间事件顺序需要在跨 topic 时额外保证。

### 方案 B：统一 topic + 按 eventType 分区

分区键为 `eventType`，同类型事件进入同一分区。

**放弃理由**：无法保证同一租户/数据源的管线事件顺序，因为同一租户的不同类型事件会分散到不同分区。

### 方案 C：分层 topic：控制面 + 数据面

控制事件（`IngestionRequested`、`ProposalApproved`）走 `heirloom.pipeline.control`，阶段性事件走各自 topic。

**放弃理由**：增加了复杂度，但当前阶段并没有强烈的控制面/数据面分离需求。统一 topic 已能满足。

## 相关 ADR

- [ADR-038](./038-raw-to-ontology-pipeline.md) — raw 到 ontology 的事件驱动管线
