# ADR-044: 数据新鲜度与缓存策略

## 状态

Proposed

## 日期

2026-07-08

## 上下文

Heirloom 的 DuckDB raw store 是客户源数据的**缓存副本**（ADR-037）。按需同步模式下，查询执行前需要判断：

1. 目标 raw 表是否已在 DuckDB 中？
2. 副本数据是否“足够新鲜”？
3. 如果不新鲜，是自动刷新还是返回过期数据并告警？

同时，频繁的分析查询可能重复拉取相同数据，需要结果级缓存来降低源系统压力和查询延迟。

## 决策

**采用“表级 freshness 元数据 + 可配置 TTL + 查询时自动刷新”的策略管理 raw 数据新鲜度；对查询结果启用短时效缓存。**

### Freshness 模型

Freshness 元数据同时维护在**PostgreSQL**（主索引）和 **DuckDB**（本地副本，用于离线分析）：

```sql
-- PostgreSQL / DuckDB
CREATE TABLE _heirloom_freshness (
    tenant_id VARCHAR,
    table_fqn VARCHAR,
    source_type VARCHAR,           -- postgresql / mysql / api
    source_fqn VARCHAR,            -- 数据源 FQN
    last_synced_at TIMESTAMP,      -- 上次成功同步时间（必有）
    source_max_ts TIMESTAMP,       -- 源系统数据最大更新时间（数据源支持时）
    row_count BIGINT,              -- 同步后行数
    table_hash VARCHAR,            -- 内容哈希（可选，大表可跳过）
    sync_status VARCHAR,           -- SUCCESS / FAILED / IN_PROGRESS
    sync_mode VARCHAR,             -- ON_DEMAND / SCHEDULED / CDC
    PRIMARY KEY (tenant_id, table_fqn)
);
```

**查询流程优先查 PostgreSQL**：避免为 freshness 检查而打开 DuckDB 文件。DuckDB 内的副本用于元数据分析（如“哪些表最久未同步”）。

### Freshness 检查流程

```
用户发起 raw/hybrid 查询
        │
        ▼
Query Router 解析依赖的 raw 表
        │
        ▼
查询 PostgreSQL 中的 _heirloom_freshness
        │
        ├─ 无记录或 DuckDB 文件不存在 → 发布 IngestionRequested，返回 jobId（同步）或阻塞等待
        ├─ source_max_ts 不存在 → 使用 last_synced_at 计算 staleness
        ├─ 已过期 → 根据 policy 决定：刷新 / 告警 / 拒绝
        └─ 未过期 → 直接执行查询
```

**同步 vs 异步刷新**：
- 同步刷新：Query Router 等待 Ingestion 完成（有超时），适合小表/低延迟场景。
- 异步刷新：Query Router 返回 `202 Accepted` 和 `jobId`，客户端轮询结果，适合大表。

### TTL 策略

TTL 按数据源类型和表重要性可配置：

| 数据源类型 | 默认 TTL | 说明 |
|-----------|---------|------|
| 配置型表（如客户主数据） | 1 小时 | 变化较慢，可接受较长缓存 |
| 交易型表（如订单） | 5 分钟 | 变化快，需要较频繁刷新 |
| 日志型表（如审计日志） | 24 小时 | append-only，可接受长缓存 |
| 实时看板 | 0（实时） | 每次查询强制刷新 |

TTL 可在 `DiscoverySource` 或表级元数据中配置：

```json
{
  "tableFQN": "prod.pg.orders_db.public.orders",
  "freshnessPolicy": {
    "ttlSeconds": 300,
    "onExpired": "REFRESH",     // REFRESH / WARN / BLOCK
    "maxStalenessSeconds": 600  // 超过此值即使 WARN 也不允许查询
  }
}
```

### 同步触发方式

| 方式 | 触发条件 | 适用场景 |
|------|---------|---------|
| **查询时刷新** | 查询发现过期，自动阻塞并触发 Ingestion | 默认行为 |
| **后台定时刷新** | 按 TTL 调度，预先把热表同步到 DuckDB | 热数据、看板场景 |
| **CDC 驱动刷新** | 源系统变更通过 Kafka 触发增量同步 | 实时性要求高的场景 |
| **手动刷新** | 用户/API 显式触发 | 分析前准备数据 |

### 增量刷新

对于 append-only 表（如日志、事件），支持基于 `source_max_ts` 的增量刷新：

```sql
INSERT INTO raw.events
SELECT * FROM source.events
WHERE updated_at > (SELECT source_max_ts FROM _heirloom_freshness WHERE table_fqn = '...');
```

增量刷新需要数据源支持按时间戳过滤。不支持的数据源回退到全量刷新。

### 结果缓存

除表级 freshness 外，对查询结果启用短时效缓存：

```java
public record QueryResultCacheKey(
    String tenantId,
    String queryHash,           // DSL 规范化后的哈希
    List<String> rawTableFQNs,
    String freshnessSnapshot,   // 依赖表最后一次 sync 时间拼接
    String role                 // 不同 Role 看到的内容不同
) {}
```

缓存策略：

- **TTL**：默认 5 分钟，raw 查询可配置。
- **失效条件**：
  - 依赖 raw 表 freshness 更新（主要）
  - Role 的 `RawQueryRestrictions` 变更（影响可见性）
  - Semantic Layer 的 `PerspectiveEngine` 配置变更（仅语义/混合查询）
- **存储**：可选 Redis 或本地 Caffeine；多实例部署推荐 Redis。
- **不缓存**：包含 `async=true`、结果行数超过阈值、或用户显式 `cache: false` 的查询。

### 用户可见的 Freshness 信息

查询结果 meta 中返回 freshness：

```json
{
  "meta": {
    "freshness": {
      "tables": [
        {
          "tableFQN": "prod.pg.orders_db.public.orders",
          "lastSyncedAt": "2026-07-08T08:00:00Z",
          "sourceMaxTs": "2026-07-08T07:55:00Z",
          "stalenessSeconds": 300
        }
      ],
      "isStale": false,
      "policy": "REFRESH_ON_EXPIRE"
    }
  }
}
```

## 后果

**积极**：
- 用户清楚知道数据新鲜度，避免基于过期数据做决策。
- 合理的 TTL 和结果缓存显著降低源系统压力和查询延迟。
- 多种同步方式可按场景组合，灵活度高。

**消极**：
- Freshness 元数据需要持久化并维护，增加系统复杂度。
- 自动刷新可能导致首次查询延迟高，影响用户体验。
- 结果缓存与权限变更的同步需要仔细设计，避免返回旧权限下的数据。
- CDC 驱动刷新需要额外基础设施和配置。

## 备选方案

### 方案 A：无 freshness 管理，每次查询强制刷新

每次 raw 查询都从源系统重新拉取数据。

**放弃理由**：
- 源系统压力大，查询延迟高。
- 频繁相同查询浪费资源。

### 方案 B：完全实时 CDC，不缓存

所有表通过 CDC 实时同步到 DuckDB。

**放弃理由**：
- 实施复杂度高，需要源系统开启 WAL/Debezium。
- 并非所有数据源都支持 CDC（如 REST API）。
- 过度实时对大多数分析场景不必要。

### 方案 C：表级物化视图 + 自动刷新

使用 DuckDB 的 `CREATE MATERIALIZED VIEW` 和 `REFRESH` 机制。

**放弃理由**：
- DuckDB 物化视图刷新策略不够灵活，难以与 Heirloom 的权限、租户、source 概念集成。
- 需要自定义元数据层来管理多租户和 source 映射。

## 相关 ADR

- [ADR-037](./037-duckdb-raw-store.md) — DuckDB raw store
- [ADR-038](./038-raw-to-ontology-pipeline.md) — raw 到 ontology 事件驱动管线
- [ADR-040](./040-query-router.md) — Query Router
- [ADR-042](./042-multi-tenant-deployment.md) — 多租户与部署架构
- [ADR-043](./043-consumer-layer-dsl-extension.md) — 消费层与 DSL 扩展
