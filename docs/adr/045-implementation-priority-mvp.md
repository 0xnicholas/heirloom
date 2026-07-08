# ADR-045: 实现优先级与 MVP 范围

## 状态

Proposed

## 日期

2026-07-08

## 上下文

经过 ADR-036 ~ ADR-044 的讨论，Heirloom 扩展架构已覆盖：

- Semantic Layer 模块化（ADR-036）
- DuckDB raw store（ADR-037）
- raw → ontology 事件驱动管线（ADR-038）
- Kafka topic 组织（ADR-039）
- Query Router（ADR-040）
- Profiling / Entity Resolution / Alignment 接口（ADR-041）
- 多租户部署（ADR-042）
- 消费层 DSL 扩展（ADR-043）
- 数据新鲜度与缓存（ADR-044）

这些架构决策如果一次性全部实现，工程量和风险都过高。需要划分阶段，明确 MVP（Minimum Viable Product）范围，以便逐步验证核心假设。

## 决策

**将扩展架构分为三个阶段：MVP → 增强 → 规模化。MVP 优先验证“Semantic Layer 模块化 + DuckDB raw store + Query Router + 消费层 DSL”这一核心闭环。**

### 阶段划分

| 阶段 | 目标 | 包含 ADR | 预计周期 |
|------|------|---------|---------|
| **MVP** | 验证语义层模块化和 raw 下钻能力 | ADR-036, ADR-037, ADR-040, ADR-043, ADR-044（核心部分） | 6-8 周（方向性估计） |
| **增强** | 自动化 raw → ontology 转换 | ADR-038, ADR-039, ADR-041，以及 ADR-044 增强 | 8-10 周（方向性估计） |
| **规模化** | 多租户 SaaS 化 | ADR-042（完整实现），以及 ADR-043/044 的性能优化 | 6-8 周（方向性估计） |

### MVP 范围（Phase 1）

#### 必须实现

1. **ADR-036：Semantic Layer 模块化**
   - 创建 `heirloom-core` 模块
   - 迁移 Discovery SPI（`SchemaExtractor`、`DiscoveryConfig`、`RawSchema` 等）
   - 创建 `heirloom-connector-postgres`
   - `heirloom-server` 改为组装器
   - 本阶段只迁移 PostgreSQL Connector，MySQL Connector 暂留

2. **ADR-037：DuckDB raw store**
   - 每租户（或单租户 MVP 中“默认租户”）一个 DuckDB 文件
   - 本地文件存储（MVP 不引入对象存储）
   - 按需同步：用户触发/API 触发
   - 支持 raw SQL 查询（受 `RawQueryAuthorizer` 限制）

3. **ADR-040：Query Router**
   - 支持 `semantic`、`raw`、`hybrid` 三种模式
   - 混合查询支持单 Resource → raw 表下钻
   - 基础 `RawQueryAuthorizer`：白名单表/列、禁止 DDL/DML

4. **ADR-043：消费层 DSL 扩展**
   - REST API `POST /v1/query` 支持扩展 DSL
   - Python SDK 增加 `semantic_query`、`raw_query`、`hybrid_query`
   - Workshop 增加 Raw Query 编辑器（简单文本/JSON 输入）
   - 本阶段 GraphQL 扩展可选

5. **ADR-044：数据新鲜度（核心部分）**
   - PostgreSQL 中维护 `_heirloom_freshness` 元数据表（DuckDB 内保留副本）
   - 查询时检查 freshness，过期自动触发同步
   - 固定 TTL（如 5 分钟），MVP 不开放复杂策略配置

#### MVP 不实现

- Kafka 事件管线（ADR-038、ADR-039）：MVP 中线性同步调用，不引入 Kafka。
- Profiling / Entity Resolution / Alignment（ADR-041）：MVP 中只保留现有 `InferencePipeline`，不增强。
- 完整多租户（ADR-042）：MVP 中不修改现有表结构，只在新增模块（DuckDB 路径、freshness 表）中预留 `tenant_id` 字段。现有业务表的多租户改造推迟到规模化阶段。
- 对象存储持久化：MVP 中 DuckDB 文件存本地磁盘。
- 结果缓存：MVP 中不实现 Redis 结果缓存。
- 异步查询：MVP 中同步执行，有超时限制。

### 增强阶段（Phase 2）

1. **引入 Kafka 事件管线（ADR-038、ADR-039）**
   - 把 Ingestion、Discovery、Profiling、Alignment、ER、Ontology Proposal 串成事件驱动管线。
   - 支持异步人工审批。

2. **实现 Profiling / Alignment / Entity Resolution（ADR-041）**
   - 增强 `InferencePipeline` 输入。
   - 自动生成更高质量的 Resource Type 提案。

3. **增强消费层**
   - MCP Server 增加 raw query tools。
   - Workshop Query Builder 支持可视化构建 raw/hybrid 查询。

4. **增强新鲜度策略**
   - 可配置 TTL、后台定时刷新、CDC 驱动刷新。

### 阶段间关系

三个阶段原则上顺序推进，但允许部分工作提前并行：

- **MVP 期间**：可并行设计 Kafka topic schema 和事件格式（ADR-039），为增强阶段做准备。
- **增强阶段期间**：可并行设计多租户表结构迁移方案（ADR-042），但不上线。
- **规模化阶段**：必须在 MVP 和增强阶段稳定后启动，因为涉及现有数据表结构变更。

### 规模化阶段（Phase 3）

1. **完整多租户实现（ADR-042）**
   - `tenant_id` 自动注入所有查询。
   - DuckDB 文件上对象存储 + 本地缓存。
   - 租户级资源限制和 on/offboarding。

2. **结果缓存与性能优化**
   - Redis 结果缓存。
   - 异步查询与长任务管理。

3. **GraphQL 完整集成（ADR-043 补充）**

## 依赖关系

```
MVP
├── ADR-036（模块化）
│   └── 为所有后续工作奠定基础
├── ADR-037（DuckDB raw store）
│   └── 依赖 ADR-036 的 Discovery SPI
├── ADR-040（Query Router）
│   └── 依赖 ADR-036 的 Semantic Layer 和 ADR-037 的 DuckDB
├── ADR-043（消费层 DSL）
│   └── 依赖 ADR-040 的 Query Router
└── ADR-044（Freshness 核心）
    └── 依赖 ADR-037 的 DuckDB

增强
├── ADR-038 / ADR-039（事件管线 / Kafka）
│   └── 依赖 MVP 中的 Ingestion 和 Discovery
├── ADR-041（Profiling/ER/Alignment）
│   └── 依赖 ADR-037 的 DuckDB 和 ADR-038 的管线
└── ADR-044 增强
    └── 依赖 ADR-038 的 Kafka

规模化
└── ADR-042（多租户）
    └── 依赖前面所有阶段稳定运行
```

## 各阶段验证标准

### MVP 退出标准

1. `heirloom-core` 能独立编译，ArchUnit 测试验证不依赖 Spring Boot 和 Connector。
2. PostgreSQL Connector 作为独立模块运行，Discovery 端到端测试通过。
3. 用户可以通过 REST/SDK 对 DuckDB 中的 raw 表执行查询。
4. 用户可以通过 hybrid 模式从已发布的 Resource 下钻到关联 raw 表。
5. 过期 raw 表在查询时自动刷新，用户能在结果 meta 中看到 freshness 信息。
6. 现有语义查询功能（Schema Registry、Action Pipeline、Knowledge Base）测试全部通过。

### 增强阶段退出标准

1. Kafka 事件管线跑通 raw → schema → proposal 全流程。
2. Profiling / Alignment / Entity Resolution 至少提供基础实现并集成到 InferencePipeline。
3. 自动生成 Resource Type 提案的质量评分（人工抽样）达到可接受水平。

### 规模化阶段退出标准

1. 所有业务表和元数据表支持 `tenant_id` 隔离。
2. DuckDB 文件可持久化到对象存储并在多个实例间共享。
3. 通过压力测试验证租户级资源限制生效。

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| 模块化迁移破坏现有测试 | 先迁接口，再迁实现；每步运行完整测试套件 |
| DuckDB 首次查询延迟过高 | MVP 中允许手动预同步；后续引入后台刷新 |
| Raw 查询绕过安全边界 | `RawQueryAuthorizer` 白名单机制；默认只允许只读 |
| 多租户概念引入过晚 | MVP 中新增模块预留 `tenant_id`，现有表结构不动，降低未来改造成本 |
| 范围蔓延 | 严格按本 ADR 的 Must/Not 清单执行，新增范围需重新评估 |

## 备选方案

### 方案 A：一次性实现所有 ADR

把所有 9 个 ADR 在一个大版本中实现。

**放弃理由**：
- 开发周期过长，无法及时验证核心假设。
- 风险集中，一旦某个架构决策错误，返工成本高。
- 团队认知负载过大，容易在实现中迷失优先级。

### 方案 B：先做最完整的 raw → ontology 自动化管线

先实现 ADR-038/039/041，再实现 Query Router。

**放弃理由**：
- 自动化管线价值建立在“raw store 已经可用且消费层能查询”之上。
- 如果用户无法查询 raw 数据，自动化产出的 ontology 也无法验证。

## 相关 ADR

- [ADR-036](./036-semantic-layer-modularization.md) — Semantic Layer 模块化
- [ADR-037](./037-duckdb-raw-store.md) — DuckDB raw store
- [ADR-038](./038-raw-to-ontology-pipeline.md) — raw 到 ontology 事件驱动管线
- [ADR-039](./039-kafka-pipeline-topics.md) — Kafka topic 组织策略
- [ADR-040](./040-query-router.md) — Query Router
- [ADR-041](./041-profiling-entity-resolution-alignment-contracts.md) — Profiling/ER/Alignment 接口契约
- [ADR-042](./042-multi-tenant-deployment.md) — 多租户与部署架构
- [ADR-043](./043-consumer-layer-dsl-extension.md) — 消费层与 DSL 扩展
- [ADR-044](./044-data-freshness-caching.md) — 数据新鲜度与缓存策略
