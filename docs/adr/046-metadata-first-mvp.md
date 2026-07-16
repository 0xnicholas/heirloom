# ADR-046: 元数据优先 MVP——取代 ADR-045

## 状态

Proposed

## 日期

2026-07-16

## 上下文

ADR-045 定义的 MVP 范围为「模块化 + DuckDB raw store + Query Router + 消费层 DSL 扩展 + Freshness 核心」，将 Profiling/Alignment/ER 推迟到增强阶段。该方案将数据建设能力过度推迟——Heirloom 当前元数据层仅 4 个实体（TableEntity、DomainEntity、LineageEntity、TableProfileEntity），对标 OpenMetadata（80+ 实体）存在数量级差距。经过与 OpenMetadata 的详细对比（见 `docs/references/heirloom-vs-openmetadata.md`），决定调整优先级。

同时，用户反馈 Heirloom「偏科」——第二层（语义操作）基本成熟，第一层（数据建设）几乎空白。KuickDB raw store + Query Router 解决的是「对已有 raw 数据的查询能力」，而当前 Heirloom 连 raw 数据的**元数据目录**都未建立完备——在 raw store 之上做查询路由是空中楼阁。

## 决策

**采用「元数据优先」策略，重新定义 MVP 范围：先夯实元数据目录基础（实体补齐 + Profiling + 语义推断升级），DuckDB raw store 和 Query Router 推迟到增强阶段。**

### 新 MVP 范围（取代 ADR-045 的 MVP）

| 包含 | 说明 | 原 ADR-045 状态 |
|------|------|:--:|
| **ADR-036：模块化** | `heirloom-core` + `heirloom-connector-postgres` + `heirloom-connector-mysql` + `heirloom-server` | 保留 |
| **元数据实体补齐** | Classification + Tag（两层标签体系）、Domain 层次化、Column 结构化 record、Table 增强 6 字段 | **新增** |
| **Profiling 引擎** | JDBC 直查，7 列级指标 + 7 种 DataClass 规则推断 + 5 维质量评分 | **新增（原→增强）** |
| **InferencePipeline 升级** | InferenceContext（含 Profile + Tags + Alignment）、6 条规则增强、Alignment 第 7 条规则 | **新增（原→增强）** |
| **API 层** | 5 个新 REST Controller（Column/Classification/Tag/Profiling/Alignment）+ Discovery 联动 | **新增** |

| 推迟 | 说明 | 新阶段 |
|------|------|--------|
| **ADR-037：DuckDB raw store** | raw 数据的元数据尚不齐备，上 DuckDB 为时过早 | 增强阶段 |
| **ADR-040：Query Router** | 需要 raw store 就绪。推迟 | 增强阶段 |
| **ADR-043：消费层 DSL 扩展** | 需要 Query Router 就绪。推迟 | 增强阶段 |
| **ADR-044：Freshness 核心** | 需要 raw store 就绪。Profiling 中嵌入 freshness 检测替代 | 增强阶段 |
| **ADR-041 Entity Resolution** | 增强阶段——Profiling + Alignment 完成后再做 | 增强阶段 |

### 阶段重划

```
Phase 0  模块化          (1.5–2w)   ← ADR-036 不变
Phase 1  元数据实体补齐    (1.5–2w)   ← 新
Phase 2  Profiling 引擎   (1.5–2w)   ← 原 ADR-045 "增强" → 提升到 MVP
Phase 3  Inference升级    (1.5–2w)   ← 新
Phase 4  API + Workshop   (1–1.5w)   ← 新
─────────────────────────────────────────
Phase 5  DuckDB + Router  (6–8w)     ← 原 ADR-045 MVP → 推后
Phase 6  Kafka 管线       (8–10w)    ← 原 ADR-045 增强
Phase 7  多租户            (6–8w)     ← 原 ADR-045 规模化
```

### 实体数量对比

| 维度 | 当前 | ADR-045 后 | 本 ADR 后 |
|------|:---:|:---:|:---:|
| 元数据实体 | 4 | 4 | ~18 |
| Profiling 指标 | 3 | 3 | 7 + DataClass |
| 标签体系 | 0 | 0 | Classification + Tag |
| InferencePipeline 规则 | 6 | 6 | 7 |

## 后果

**积极**：
- 「第一层」和「第二层」的不平衡得到实质性改善
- Profiling 数据直接提升 InferencePipeline 推断质量（DataClass → FieldType，topValues → StateMachine）
- Alignment 是 Heirloom 独有的语义对齐能力，无法从 OM 借鉴——必须自己从元数据层起步建设
- 元数据层就绪后，DuckDB raw store 和 Query Router 有坚实的数据基础

**消极**：
- DuckDB raw store 推迟到 Phase 5，意味着用户在短期内仍无法对 raw 表做自由 SQL 分析
- Query Router 推迟意味着语义查询和 raw 查询仍走两条路径，不统一
- Freshness 不做独立元数据表，靠 Profiling 的 `profiledAt` 时间戳间接判断——不如独立 freshness 系统精确
- Entity Resolution 推迟到 Phase 6，跨源去重识别实体的能力需要更长时间

## 备选方案

### 方案 A：保持 ADR-045 不变（已放弃）

先做 DuckDB + Query Router，Profiling 留后期。

**放弃理由**：在没有元数据目录的情况下对 raw 表做查询路由——相当于在没整理好的仓库里装自动导航系统。元数据层是数据建设的基础设施，不应推后。

### 方案 B：两轨并行（已放弃）

模块化+元数据实体 与 DuckDB+Query Router 同阶段并行推进。

**放弃理由**：协调成本高，两条轨道有依赖关系无法完全独立。元数据实体（尤其是 Column 模型）是 raw store 表映射的前置条件。

## 相关 ADR

- [ADR-036](./036-semantic-layer-modularization.md) — 语义层模块化（保留，纳入 Phase 0）
- [ADR-041](./041-profiling-entity-resolution-alignment-contracts.md) — Profiling/ER/Alignment 接口（Profiling + Alignment 提前，ER 推迟）
- [ADR-045](./045-implementation-priority-mvp.md) — 原 MVP 范围（**被本 ADR 取代**）
- [设计规格](../superpowers/specs/2026-07-16-metadata-layer-foundation.md) — 完整设计文档
- [OpenMetadata 对比](../references/heirloom-vs-openmetadata.md)
