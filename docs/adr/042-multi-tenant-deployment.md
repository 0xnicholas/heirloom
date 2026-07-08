# ADR-042: 多租户与部署架构

## 状态

Proposed

## 日期

2026-07-08

## 上下文

Heirloom 从面向单一组织的部署演化为面向多个客户（租户）的 SaaS 平台。引入 DuckDB raw store 和 Kafka 事件管线后，多租户问题变得更加具体：

1. **数据隔离**：不同租户的客户 raw 数据、DuckDB 文件、元数据必须严格隔离。
2. **计算隔离**：一个租户的查询分析不应显著影响其他租户。
3. **资源管理**：DuckDB 文件、Kafka 分区、PostgreSQL 数据随租户增长而增长，需要可扩展的资源管理。
4. **部署复杂度**：需要在成本、隔离性和运维复杂度之间权衡。

## 决策

**采用“共享服务实例 + 租户级数据隔离”的多租户架构。**

### 部署拓扑

```
                    Load Balancer
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
   Heirloom Server   Heirloom Server   Heirloom Server
   (stateless)       (stateless)       (stateless)
         │               │               │
         └───────────────┼───────────────┘
                         ▼
              Shared PostgreSQL
              (metadata + Event Log)
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
      Kafka Cluster   Object Storage    (可选) Redis
      (pipeline       (DuckDB 文件       (分布式锁 /
       events)        主副本)            租户级限流缓存)
```

### 关键设计点

#### 1. 服务实例：共享、无状态

- 多个 Heirloom Server 实例共享同一套代码和配置。
- 实例本身不保存租户状态，租户上下文随请求传递。
- 水平扩展时新增实例即可，不需要按租户分配实例。

#### 2. PostgreSQL：共享数据库 + 行级租户隔离

- 所有租户共享同一个 PostgreSQL 数据库实例。
- 每张业务表新增 `tenant_id` 列（现有表需要迁移）。
- 通过 Repository 基类自动注入 `tenant_id`，并结合 AOP/断言防止业务代码绕过；Hibernate Filter 或 PostgreSQL RLS 可作为额外补充。
- 元数据表（`resource_types`、`mapping_rules`、`proposals` 等）同样包含 `tenant_id`。
- 未来若某租户需要独立数据库，可通过分库策略迁移。

> **迁移说明**：当前 Heirloom 表结构未包含 `tenant_id`。本 ADR 生效后需要一次数据迁移：为单租户部署补充默认 `tenant_id`，后续新增租户按正常流程创建。

#### 3. DuckDB raw store：每租户独立文件

- 每个租户拥有独立的 DuckDB 文件目录：
  ```
  /data/duckdb/{tenantId}/
    ├── raw.db
    ├── raw.db.wal
    └── metadata.json
  ```
- **主存储**：DuckDB 文件主副本放在对象存储（如 S3），保证持久性和可恢复性。
- **运行时**：服务实例将租户 DuckDB 文件按需下载到本地 SSD 缓存，打开连接执行查询，关闭时上传变更（若写入）。
- **连接管理**：使用租户级连接池/缓存，避免每次查询都重新打开文件；但限制同时打开的租户连接数，防止内存耗尽。

#### 4. Kafka：共享集群 + 租户分区

- 所有租户共享同一个 Kafka 集群。
- 管线事件 topic 按 `tenantId + sourceId` 分区（见 ADR-039）。
- 消费者组按阶段划分，所有实例共同消费。

#### 5. 租户上下文传递

```java
public class TenantContext {
    private String tenantId;
    private String callerRole;
    private String callerId;
}
```

- 通过 HTTP Header `X-Tenant-Id` 传入，由 Gateway/Controller 解析并放入 ThreadLocal / MDC。
- Repository 层自动从 `TenantContext` 获取 `tenant_id` 并附加到查询。
- 请求结束、线程归还线程池或进入异步任务/Kafka consumer 时，必须清理 `TenantContext`，防止租户上下文泄漏。

#### 6. 资源限制与隔离

| 资源 | 限制策略 | 实现方式 |
|------|---------|---------|
| DuckDB 文件大小 | 单租户最大 100GB（可配置），超限触发清理或告警 | 定时扫描 + 配置阈值 |
| 单次查询返回行数 | 由 `RawQueryRestrictions.maxRows` 控制 | 执行前注入 `LIMIT` |
| 并发查询 | 每租户最大并发数限制 | 租户级信号量 / Redis 分布式计数器 |
| 查询超时 | raw 查询默认 60s | `Future.get(timeout)` |
| Kafka 消费速率 | 按 consumer group 配置 `max.poll.records` 和 `fetch.min.bytes` | Kafka consumer 配置 |
| Profiling 采样 | 大表默认采样 10%（可配置） | `TABLESAMPLE` 或 `USING SAMPLE` |
| DuckDB 并发连接 | 单实例最多同时打开 N 个租户连接 | LRU 连接缓存 |

#### 7. 租户生命周期

| 阶段 | 操作 |
|------|------|
| **Onboarding** | 创建租户记录 → 在 PostgreSQL 中初始化默认 ontology → 在对象存储创建 DuckDB 目录 → 发送 `TenantCreated` 事件 |
| **正常运行** | 租户通过 `X-Tenant-Id` 访问服务，资源受上述限制 |
| **Offboarding** | 冻结租户 → 归档 DuckDB 文件和 PostgreSQL 数据 → 保留审计日志 → 删除活跃数据 |

#### 8. 高可用与灾备

- **PostgreSQL**：使用主从复制 + 定期快照备份。
- **Kafka**：多 broker + 副本因子 ≥ 3。
- **DuckDB 文件**：对象存储本身具备多副本；定期生成时间点快照。
- **服务实例**：无状态，故障时流量切换到健康实例。

## 后果

**积极**：
- 部署简单，初期成本低。
- 资源利用率高，适合租户数量多但单租户数据量中等的场景。
- 水平扩展容易，只需增加无状态服务实例。
- 与 ADR-037（每租户 DuckDB）和 ADR-039（Kafka 租户分区）自然衔接。

**消极**：
- 共享数据库和共享进程意味着隔离性弱于单租户独立部署。
- 需要严格的 `tenant_id` 过滤和 Repository 层自动注入，一旦遗漏会导致数据泄露。
- 单租户异常查询可能影响同实例其他租户（需要通过资源限制缓解）。
- DuckDB 文件从对象存储加载到本地 SSD 需要时间，首次访问延迟较高。
- 现有表结构需要迁移以支持 `tenant_id`，对当前代码影响较大。
- 租户 on/offboarding 流程需要额外设计和实现。

## 备选方案

### 方案 A：每租户独立部署

每个租户拥有独立的 Heirloom Server、PostgreSQL、Kafka、DuckDB。

**放弃理由**：
- 运维成本极高，每个租户都需要独立监控、升级、备份。
- 资源利用率低，小租户无法分摊基础设施成本。
- 与当前“平台型”定位冲突。

### 方案 B：数据库 Schema 级隔离

每个租户在共享 PostgreSQL 实例中有独立 schema。

**放弃理由**：
- 相比行级隔离，schema 隔离增加了连接管理和迁移复杂度。
- 对当前阶段收益有限，行级隔离加 careful query 足够。
- 未来可作为行级隔离之上的可选升级路径。

### 方案 C：完全无服务器（Serverless）

每个请求启动独立容器，按调用付费。

**放弃理由**：
- DuckDB 持久文件和 Kafka 消费者与无服务器模型冲突。
- 当前阶段过度设计，且团队运维经验不足。

## 相关 ADR

- [ADR-003](./003-storage-separation.md) — 存储层分离
- [ADR-037](./037-duckdb-raw-store.md) — DuckDB raw store
- [ADR-039](./039-kafka-pipeline-topics.md) — Kafka topic 组织策略
- [ADR-040](./040-query-router.md) — Query Router
