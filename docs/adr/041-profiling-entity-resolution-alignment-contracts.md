# ADR-041: Profiling / Entity Resolution / Alignment 接口契约

## 状态

Proposed

## 日期

2026-07-08

## 上下文

在 ADR-038 中，我们定义了 raw 数据到业务本体语义的 8 阶段事件驱动管线。其中第 3~5 阶段（Profiling、Entity Resolution、Semantic Alignment）是连接“原始数据结构”与“业务语义”的关键转换层。这三个阶段需要在核心模块中定义稳定的接口契约，以便：

1. 不同算法实现可以替换（如基于规则的实体解析 vs 基于机器学习的实体解析）。
2. 管线其他阶段只依赖契约，不依赖具体实现。
3. 单元测试和集成测试可以独立验证每个阶段。

本 ADR 定义这三个阶段的输入、输出和核心接口。

## 决策

**在 `heirloom-core` 中定义 `ProfilingService`、`EntityResolutionService` 和 `AlignmentService` 接口，以及对应的结果数据结构。**

这三个服务属于语义核心的“辅助转换”能力，与 Schema Registry、InferencePipeline 协同工作，但本身不直接持久化业务实体。

### 设计原则

1. **输入基于 DuckDB**：三个阶段都从 DuckDB raw store 读取数据，不直接连接客户源系统。
2. **输出是可合并的建议**：结果不是最终决策，而是供 InferencePipeline 和人工治理参考的建议。
3. **置信度驱动**：每个建议都带 `confidence` 分数，低置信度建议进入 Proposal 审批流程。
4. **无状态服务**：服务本身不保存状态，状态通过事件和持久化实体（`ProfileReport`、`AlignmentMap`、`EntityMap`）管理。

---

## Profiling Service

### 职责

对 DuckDB 中的 raw 表做统计分析，输出列级和表级指标，识别数据质量问题和敏感数据。

### 接口

```java
public interface ProfilingService {
    ProfileReport profile(String tenantId, String tableFQN);
}
```

### 输出结构

```java
public record ProfileReport(
    String tenantId,
    String tableFQN,
    long rowCount,
    long columnCount,
    Instant profiledAt,
    List<ColumnProfile> columns,
    double overallQualityScore
) {}

public record ColumnProfile(
    String columnName,
    String dataType,
    long nullCount,
    double nullRate,
    long distinctCount,
    double distinctRate,
    long emptyStringCount,
    String minValue,
    String maxValue,
    double averageLength,           // 字符串列
    List<ValueFrequency> topValues, // top-k 频繁值
    Set<DataClass> suspectedClasses, // PII / 枚举 / 时间戳等
    double qualityScore
) {}

public record ValueFrequency(
    String value,
    long count,
    double frequency
) {}

public enum DataClass {
    EMAIL, PHONE, CREDIT_CARD, SSN, UUID, URL,
    ENUM, BOOLEAN_LIKE, TEMPORAL, NUMERIC, TEXT, UNKNOWN
}
```

### 质量评分规则

质量评分算法可配置，默认权重如下（租户/表级可覆盖）：

| 指标 | 默认权重 | 说明 |
|------|---------|------|
| 空值率 | 30% | 空值越少越好 |
| 唯一值率 | 20% | 主键列应接近 1.0 |
| 类型一致性 | 20% | 是否所有值都符合声明类型 |
| 枚举稳定性 | 15% | 枚举值是否稳定（不频繁变化） |
| 长度一致性 | 15% | 字符串长度是否集中在合理范围 |

### 增量剖析

- 首次同步后执行**全量剖析**。
- 后续同步根据表 hash 或 CDC 变更范围执行**增量剖析**，仅重新计算受影响列的指标。
- 大表可配置采样比例（如 10% 采样），在性能和准确性之间权衡。

---

## Alignment Service

### 职责

将 raw 表中的列与已知 Resource Type 字段或语义概念对齐，输出字段映射建议。

### 接口

```java
public interface AlignmentService {
    AlignmentMap align(AlignmentRequest request);
}

public record AlignmentRequest(
    String tenantId,
    String tableFQN,
    List<String> targetOntologies,     // 可选：限定对齐目标 ontology
    boolean allowNewTypeProposal       // 是否允许建议全新 Resource Type
) {}
```

### 输出结构

```java
public record AlignmentMap(
    String tenantId,
    String tableFQN,
    Instant alignedAt,
    List<FieldAlignment> alignments,
    List<String> unmappedColumns,
    List<NewTypeSuggestion> newTypeSuggestions  // 当 allowNewTypeProposal=true 时
) {}

public record FieldAlignment(
    String columnName,
    String columnDataType,
    SemanticTarget target,
    double confidence,
    List<AlignmentSignal> signals
) {}

public record SemanticTarget(
    String ontology,          // 如 "default"
    String resourceType,      // 如 "Customer"
    String fieldName,         // 如 "email"
    String fieldDataType
) {}

public record NewTypeSuggestion(
    String proposedTypeName,
    List<String> columns,
    double confidence,
    String rationale
) {}

public record AlignmentSignal(
    String type,              // "name_similarity" / "type_match" / "value_overlap" / "glossary_match" / "profile_match"
    double score,
    String description
) {}
```

### 对齐信号来源

| 信号类型 | 说明 | 前提 |
|---------|------|------|
| `name_similarity` | 列名与已知字段名的字符串/语义相似度 | 需要已有 Resource Type 或术语表 |
| `type_match` | 原始数据类型与目标字段类型是否兼容 | 需要已有 Resource Type |
| `value_overlap` | 列值与目标字段已有值的 overlap 比例 | 目标 Resource 已有数据 |
| `glossary_match` | 列注释/描述是否匹配业务术语表 | 需要术语表 |
| `profile_match` | Profiling 结果（如 DataClass=EMAIL 对齐到 email 字段） | 需要 profiling 完成 |

### 无现有 Ontology 的处理

- 首次发现某业务领域数据时，可能没有对应 Resource Type。
- `allowNewTypeProposal=true` 时，AlignmentService 基于列名相似度和主外键关系建议全新 Resource Type。
- 这些建议进入 InferencePipeline，最终生成 Resource Type Proposal 走治理流程。

---

## Entity Resolution Service

### 职责

跨表/跨源识别同一业务实体，输出实体 ID 映射和聚类结果。

### 接口

```java
public interface EntityResolutionService {
    EntityResolutionResult resolve(
        String tenantId,
        String entityType,               // 如 "Customer"
        List<String> sourceTableFQNs,    // 参与解析的 raw 表
        List<ResolutionKey> keys         // 用于匹配的候选键
    );
}

public record ResolutionKey(
    String sourceTableFQN,
    String sourceColumn,
    ResolutionKeyType type,            // DETERMINISTIC / FUZZY
    double weight                      // 该键在综合匹配中的权重
) {}

public enum ResolutionKeyType {
    DETERMINISTIC,   // 全局唯一 ID，直接匹配
    FUZZY            // 需要相似度计算
}
```

### 输出结构

```java
public record EntityResolutionResult(
    String tenantId,
    String entityType,
    Instant resolvedAt,
    long totalRecords,
    long uniqueEntities,
    List<EntityCluster> clusters,
    List<UnmatchedRecord> unmatched
) {}

public record EntityCluster(
    String provisionalCanonicalId,   // 临时统一 ID，Resource Type 发布后转换为正式 RID
    List<EntityReference> members,   // 各源系统中的记录引用
    double confidence
) {}

public record EntityReference(
    String sourceTableFQN,
    String sourceColumn,             // 参与匹配的列
    String sourceValue,              // 原始 ID 值
    Map<String, String> rowContext   // 其他上下文列（如 email, phone）
) {}

public record UnmatchedRecord(
    String sourceTableFQN,
    String sourceColumn,
    String sourceValue,
    String reason
) {}
```

### 解析策略

| 策略 | 适用场景 |
|------|---------|
| **确定性规则** | 已有全局唯一 ID（如统一社会信用代码、手机号），直接匹配 |
| **模糊匹配** | 姓名 + 邮箱 + 电话组合相似度计算 |
| **传递闭包** | A 与 B 共享 phone，B 与 C 共享 email → A/B/C 属于同一实体 |
| **人工确认** | 置信度低于阈值时进入治理待办，人工确认后生效 |

---

## 阶段间数据流

默认执行顺序：

```
SchemaDiscovered
    │
    ▼
┌─────────────────┐
│ ProfilingService│  → ProfileReport
└─────────────────┘
    │
    ▼
┌─────────────────┐
│ AlignmentService│  → AlignmentMap
└─────────────────┘
    │
    ▼
┌─────────────────────────┐
│ EntityResolutionService │  → EntityResolutionResult
└─────────────────────────┘
    │
    ▼
InferencePipeline  → ResourceTypeProposal
```

### 可调整顺序

三个阶段并非严格线性。实际管线支持以下变体：

- **Profiling → Alignment → ER**：默认顺序，适合大多数场景。
- **Profiling → ER → Alignment**：先识别同一实体，再基于实体属性对齐字段。
- **迭代执行**：Alignment 结果可能提示新的候选 key，触发第二轮 ER；ER 结果可能修正 Alignment。

默认实现采用第一种顺序，但接口设计允许未来引入迭代或条件分支。

### 与 InferencePipeline 的集成

`InferencePipeline` 接收 `ProfileReport`、`AlignmentMap`、`EntityResolutionResult` 作为输入，与 `RawSchema` 一起生成 `ResourceTypeProposal`：

```java
public interface InferencePipeline {
    List<ResourceTypeProposal> infer(
        RawSchema rawSchema,
        ProfileReport profile,
        AlignmentMap alignment,
        EntityResolutionResult entities
    );
}
```

---

## 持久化

三个阶段的结果不是业务实体，而是转换过程中的中间产物。持久化策略：

| 结果 | 持久化位置 | 生命周期 |
|------|-----------|---------|
| `ProfileReport` | PostgreSQL `profile_reports` 表 | 保留最近 N 次，旧版本可清理 |
| `AlignmentMap` | PostgreSQL `alignment_maps` 表 | 与 Resource Type 提案关联，审批后归档 |
| `EntityResolutionResult` | PostgreSQL `entity_clusters` + `entity_references` 表 | 长期保留，作为 RID 映射依据；需遵守租户数据保留策略 |

---

## 后果

**积极**：
- 三个阶段接口清晰，可独立实现、测试和替换算法。
- InferencePipeline 输入更丰富，生成的 Resource Type 提案质量更高。
- 管线其他阶段只依赖接口契约，不受具体算法变更影响。

**消极**：
- 引入三个新的服务接口和持久化表，增加系统复杂度。
- 实体解析的置信度阈值需要调优，过低导致人工审批负担重，过高导致漏配。
- Profiling 大表可能消耗较多 DuckDB 计算资源，需要采样和限流。
- 三个阶段可能存在迭代依赖，管线编排复杂度高于纯线性流程。
- Entity Resolution 长期保存跨源 ID 映射，需要严格的数据保留和隐私控制。

## 备选方案

### 方案 A：不定义独立接口，所有逻辑内联在 InferencePipeline 中

把 profiling、alignment、entity resolution 作为 InferencePipeline 的内部步骤。

**放弃理由**：
- 算法无法独立演进和替换。
- 阶段之间无法通过事件解耦，难以单独重试或人工介入。
- 测试困难，一个 bug 会波及整个推断流程。

### 方案 B：每个阶段单独服务进程

Profiling / Alignment / Entity Resolution 各自作为独立服务，通过 Kafka 通信。

**放弃理由**：
- 当前阶段复杂度不足，引入进程间通信和网络开销没有必要。
- 与 Heirloom 当前单进程部署模式冲突，增加运维负担。

## 相关 ADR

- [ADR-023](./023-inference-pipeline.md) — InferencePipeline 设计
- [ADR-036](./036-semantic-layer-modularization.md) — Semantic Layer 模块化
- [ADR-037](./037-duckdb-raw-store.md) — DuckDB raw store
- [ADR-038](./038-raw-to-ontology-pipeline.md) — raw 到 ontology 事件驱动管线
