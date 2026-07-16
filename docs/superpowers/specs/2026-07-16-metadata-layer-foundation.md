# Heirloom 元数据层基础建设 — 设计规格

**日期**: 2026-07-16
**状态**: Draft
**参考**: OpenMetadata（`_references/OpenMetadata-main/`）、ADR-036、ADR-041、ADR-045

---

## 1. 背景

Heirloom 平台架构定义为两层（ADR-010）：

- **第一层：元数据目录**（对标 OpenMetadata）—— 自动发现和采集数据源 schema、血缘、质量、所有权、术语表
- **第二层：语义操作层**（Heirloom 独有）—— ResourceType、Abilities、StateMachine、Action Pipeline

当前第二层基本成熟（98/135 roadmap 项完成），第一层仅 4 个实体（TableEntity、DomainEntity、LineageEntity、TableProfileEntity），与对标目标 OpenMetadata（80+ 实体）存在数量级差距。

ADR-045 原定 MVP 为「模块化 + DuckDB + Query Router + DSL 扩展 + Freshness」，将 Profiling/Alignment/ER 推迟到增强阶段。经复盘，该方案将「数据建设」能力过度推迟，决定**扩展 MVP 范围**：以元数据实体补齐 + Profiling + 语义推断升级替换 DuckDB/Query Router 的优先级。

本规格基于对比文档 `docs/references/heirloom-vs-openmetadata.md`，采用「紧扣语义层需求」策略——约 18 个实体，只做语义操作层真正需要的元数据能力。

---

## 2. 模块结构

按 ADR-036 进行模块化拆分：

```
heirloom/
├── heirloom-core/                    # 语义核心 — 纯 Java，不依赖 Spring Boot
│   ├── entity/                       #   HeirloomEntity, EntityRegistry, EntityRegistration
│   ├── schema/                       #   ResourceType, Field, Ability, StateMachine, Relationship
│   ├── security/                     #   ActionPipeline, CapabilityResolver (接口), PipelineContext
│   ├── query/                        #   SemanticQuery, QueryParser, SqlGenerator (接口)
│   ├── discovery/                    #   SchemaExtractor SPI, DiscoveryConfig, RawSchema/RawTable/RawColumn
│   ├── metadata/                     #   Classification, Tag, Domain, ColumnDef, TableProfileDef (接口 + record)
│   ├── profiling/                    #   ProfilingService 接口, ColumnProfileResult, ProfileReport, DataClass
│   ├── alignment/                    #   AlignmentService 接口, AlignmentMap, FieldAlignment, AlignmentSignal
│   └── repository/                   #   EntityRepository 基类
│
├── heirloom-connector/               # 连接器父模块 (pom)
│   ├── heirloom-connector-postgres/  #   PostgresSchemaExtractor
│   └── heirloom-connector-mysql/     #   MySqlSchemaExtractor
│
└── heirloom-server/                  # 组装层 — Spring Boot + REST/GraphQL
    ├── metadata/domain/              #   JPA Entity (TableEntity, ClassificationEntity, TagEntity, ...)
    ├── metadata/repository/          #   JPA Repository
    ├── metadata/web/                 #   REST Controller
    ├── profiling/service/            #   ProfilingService JDBC 实现
    ├── alignment/service/            #   AlignmentService 实现
    └── web/                          #   现有 Controller + 新增 metadata 端点
```

**关键约束：**
- `heirloom-core` 只含接口、record、enum——不含 JPA Entity、Spring Annotation、REST Controller
- `heirloom-server` 持有所有 JPA/JDBC 实现，实现 `heirloom-core` 的接口
- Connector 模块通过 `SchemaExtractor` SPI 接入，运行时由 Spring Boot 自动配置注册

**迁移策略：**
1. 先在 `heirloom-server` 内部创建 `heirloom-core` 源码目录（同 build）
2. 逐类迁移：先迁接口/record/model，再迁 SPI
3. `heirloom-server` 实现类改为实现 `heirloom-core` 接口
4. 拆出 `heirloom-connector-postgres` 和 `heirloom-connector-mysql` 为独立 Maven 模块
5. 全程保持测试通过（192 现有测试不破坏）

---

## 3. 元数据实体模型

### 3.1 新增实体

**Column**（从 Table 的 `columnsJson` JSONB 拆出为结构化 record）：

```java
// heirloom-core
public record ColumnDef(
    String name,
    String dataType,
    Integer dataLength,
    Integer numericPrecision,
    Integer numericScale,
    boolean nullable,
    String defaultValue,
    String comment,
    Integer ordinalPosition,
    List<String> tags
) {}
```

存储策略：作为 JSONB 数组存储在 `metadata_tables.columns` 中，不做独立表。Column 总是和 Table 一起查询，独立建表增加 JOIN 开销。

**Classification → Tag**（两层标签体系）：

```
Classification "PII"
  ├── Tag "PII.Email"
  ├── Tag "PII.Phone"
  └── Tag "PII.SSN"
Classification "Sensitivity"
  ├── Tag "Sensitivity.Public"
  ├── Tag "Sensitivity.Internal"
  └── Tag "Sensitivity.Confidential"
```

```java
// heirloom-core
public interface Classification extends HeirloomEntity {
    String getName();
    String getDescription();
}

public interface Tag extends HeirloomEntity {
    String getName();
    String getClassificationFQN();
    String getParentFQN();        // 父 Tag（可选，支持层次）
    String getStyle();            // 颜色/图标（workshop 显示用）
    String getDescription();
}
```

JPA 各建一张表：`metadata_classifications`、`metadata_tags`。

**Domain**（层次化领域）：

```java
// heirloom-core
public interface Domain extends HeirloomEntity {
    String getName();
    String getParentFQN();       // 父领域，null = 顶级
    String getDescription();
    String getOwner();
}
```

现有 `DomainEntity` 加 `parentFQN` 字段。

**ColumnProfile**（列级剖析结果）：

```java
// heirloom-core
public record ColumnProfileResult(
    String columnName,
    String dataType,
    long nullCount,
    double nullRate,
    long distinctCount,
    double distinctRate,
    long emptyStringCount,
    String minValue,
    String maxValue,
    Double avgLength,
    List<ValueFrequency> topValues,
    DataClass detectedClass,
    double qualityScore
) {}

public record ValueFrequency(String value, long count, double frequency) {}

public enum DataClass {
    EMAIL, PHONE,
    ENUM, BOOLEAN_LIKE, TEMPORAL, NUMERIC, TEXT, UNKNOWN
}
```

JPA 建 `column_profiles` 表（时间序追加）。

### 3.2 增强现有实体

**TableEntity** 新增字段：

| 新字段 | 类型 | 说明 |
|--------|------|------|
| `tags` | JSONB `List<String>` | 关联 Tag FQN 列表 |
| `domainFQN` | VARCHAR | 关联 Domain |
| `constraints` | JSONB | PK/FK/Unique/Check 约束列表 |
| `sourceHash` | VARCHAR | Schema 增量同步 hash |
| `lifecycle` | VARCHAR | 生命周期: Created → InUse → Deprecated → Deleted |
| `certification` | JSONB | `{level, certifiedBy, expiresAt}` |

**TableProfileEntity** 增强字段：

| 新字段 | 类型 | 说明 |
|--------|------|------|
| `nullCount` | BIGINT | 全表空值总数 |
| `distinctCount` | BIGINT | 全表去重行数 |
| `duplicateRowCount` | BIGINT | 重复行数 |
| `profiledAt` | TIMESTAMPTZ | 剖析时间 |
| `profilingDurationMs` | BIGINT | 剖析耗时 |

### 3.3 实体清单汇总

| 实体 | 类型 | heirloom-core | heirloom-server | 状态 |
|------|------|:---:|:---:|------|
| Table | 元数据 | interface | TableEntity (JPA) | 增强 |
| Column | 内嵌 record | ColumnDef | 存 JSONB | 重构 columnsJson |
| Classification | 分类 | interface | ClassificationEntity (JPA) | **新建** |
| Tag | 标签 | interface | TagEntity (JPA) | **新建** |
| Domain | 领域 | interface | DomainEntity (JPA) | 增强 |
| TableProfile | 质量快照 | interface | TableProfileEntity (JPA) | 增强 |
| ColumnProfile | 列级剖析 | record | ColumnProfileEntity (JPA) | **新建** |
| Lineage | 血缘 | interface | LineageEntity (JPA) | 不动 |

> **注**：`AlignmentService` 输出的 `AlignmentMap` / `FieldAlignment` 为计算中间产物，存储在内存中供 InferencePipeline 消费，不单独建表。`ProfilingService` 输出通过 `column_profiles` 表持久化（见 4.5）。总持久化元数据实体约 18 个（Table, ColumnDef, Classification, Tag, Domain, TableProfile, ColumnProfile, Lineage + 现有 10 个语义/平台实体）。

全部跨实体引用用 FQN 字符串，不做 JPA `@ManyToOne`。

---

## 4. Profiling 引擎

### 4.1 接口契约

```java
// heirloom-core
public interface ProfilingService {
    ProfileReport profile(String tableFQN);
    ColumnProfileResult profileColumn(String tableFQN, String columnName);
}

public record ProfileReport(
    String tableFQN,
    long rowCount,
    long columnCount,
    Instant profiledAt,
    long durationMs,
    List<ColumnProfileResult> columns,
    double overallQualityScore
) {}
```

### 4.2 实现策略

JDBC 直连执行剖析 SQL，不引入 Python 依赖：

| 指标 | SQL 策略 |
|------|---------|
| rowCount | `SELECT COUNT(*) FROM {table}` |
| nullCount / nullRate | `SELECT COUNT(*), COUNT({col}) FROM {table}` |
| distinctCount / distinctRate | `SELECT COUNT(DISTINCT {col}) FROM {table}` |
| minValue / maxValue | `SELECT MIN({col}), MAX({col}) FROM {table}` |
| avgLength | `SELECT AVG(LENGTH({col}::text)) FROM {table}` |
| emptyStringCount | `SELECT COUNT(*) FROM {table} WHERE {col} = ''` |
| topValues | `GROUP BY {col} ORDER BY cnt DESC LIMIT 10` |

**采样**：表 > 100万行，自动降为 10% 采样（`TABLESAMPLE BERNOULLI(10)`），可配置。

### 4.3 DataClass 规则推断

规则按优先级从上到下匹配，首个命中即返回，不尝试多分类。某列最多归属一个 DataClass。

```
1. topValues 全为 "true"/"false"/"0"/"1"/...  →  BOOLEAN_LIKE
2. columnName 含 "email" + avgLength > 5      →  EMAIL
3. columnName 含 "phone"/"mobile"              →  PHONE
4. distinctRate < 0.05 且 distinctCount ≤ 20   →  ENUM
5. min/max 可解析为日期                         →  TEMPORAL
6. min/max 可解析为数字                         →  NUMERIC
7. 其余                                        →  TEXT
```

`BOOLEAN_LIKE` 和 `EMAIL`/`PHONE` 排在 `ENUM` 之前——`status` 列只有 3 个值应该是 ENUM 而非 BOOLEAN_LIKE，但 `is_active` 列应优先命中 BOOLEAN 规则。具体靠 `topValues` 匹配精度（全为 true/false 字面量）区分。

`NUMERIC` 排在 `TEMPORAL` 之后——带日期格式的数字列不误判为数值。

### 4.4 质量评分

| 指标 | 默认权重 | 计算方式 |
|------|---------|---------|
| 空值率 | 30% | `(1 - nullRate) * weight` |
| 唯一值率 | 20% | `distinctRate * weight` |
| 类型一致性 | 20% | 数值列无字母混入 → 满分 |
| 枚举稳定性 | 15% | 枚举列 top5 占比 > 80% → 满分 |
| 长度一致性 | 15% | 字符串长度标准差 / 平均值 |

### 4.5 存储与迁移

列级剖析结果写入新表 `column_profiles`（V20），每次剖析追加新行（时间序），保留最近 5 次历史。

现有 `TableProfileEntity.columnProfiles` JSONB 字段**保留但不继续写入**。旧字段中已有的历史数据不动（DELETE/TRUNCATE 不做），新剖析结果只写 `column_profiles` 表。`TableProfileEntity` 仅存表级聚合（rowCount + overallQualityScore）。

`ColumnProfilingResource` 的查询 API 只读新表，不查询旧 JSONB 字段。

旧 `columnProfiles` JSONB 数据不做自动回填——数据量小、格式松散（非结构化 map），写入成本高于收益。下次 Profiling 执行时自然覆盖为新格式数据。历史 columnProfiles 数据可通过 `GET /v1/tables/{fqn}` 的旧字段返回，但未来大版本中该 JSONB 列将被 DROP。

### 4.6 引擎限制

MVP 阶段 Profiling 仅支持 PostgreSQL。MySQL 不支持 `TABLESAMPLE BERNOULLI` 采样语法，且 `AVG(LENGTH(col::text))` 的 `::text` cast 为 PG 专有。MySQL Connector 在 Phase 1 中仅提供 Schema 提取能力，不参与 Profiling。

采样策略通过接口抽象，为未来扩展预留接入点：

```java
public interface SamplingStrategy {
    String apply(String tableSql, long estimatedRows);
}
```

MVP 仅提供 `PostgresSamplingStrategy`（`TABLESAMPLE BERNOULLI(10)`）。MySQL 实现留 Phase 5+。

---

## 5. InferencePipeline 升级

### 5.1 输入扩展

`InferenceRule` 接口签名从 `infer(RawSchema)` 升级为 `infer(InferenceContext)`：

```java
// heirloom-core (before)
public interface InferenceRule {
    List<ResourceTypeProposal> infer(RawSchema schema);
}

// heirloom-core (after)
public interface InferenceRule {
    List<ResourceTypeProposal> infer(InferenceContext ctx);
}

public interface InferencePipeline {
    List<ResourceTypeProposal> infer(InferenceContext ctx);
}

public record InferenceContext(
    RawSchema rawSchema,
    ProfileReport profile,        // 新
    AlignmentMap alignment,       // 新
    List<String> tableTags,       // 新
    String domainFQN              // 新
) {}
```

**迁移策略**：所有 6 条现有规则（位于 `discovery/inference/rules/` 目录下）同步修改签名，一次性完成。不提供 `default infer(RawSchema)` 兼容方法——因为调用方（InferencePipeline）是内部代码，与规则同在一个模块，不存在外部消费者。DiscoveryService 和 DiscoveryRunner 只调用 InferencePipeline，不直接调用 InferenceRule。

### 5.2 规则增强

| 规则 | Profiling 增强 |
|------|---------------|
| FieldMapperInference | `detectedClass=EMAIL` → 字段标注 ENUM-like；`nullRate>0.5` → 标记可空 |
| AbilityInference | `rowCount<100` → 疑似配置表加 FREEZE；`distinctRate>0.9` 的列 → 加 KEY |
| StateMachineInference | `topValues` 精确枚举所有状态值 → 完整状态机 |
| TypeNameInference | 无变化 |
| RelationshipInference | 无变化 |
| DescriptionInference | 无变化 |

### 5.3 新增 Alignment 规则（第 7 条）

```java
// heirloom-core
public interface AlignmentService {
    AlignmentMap align(AlignmentRequest request);
}

public record AlignmentRequest(
    String tableFQN,
    List<String> targetOntologies,
    boolean allowNewType
) {}

public record AlignmentMap(
    String tableFQN,
    List<FieldAlignment> alignments,
    List<String> unmappedColumns,
    List<NewTypeSuggestion> newTypeSuggestions
) {}

public record FieldAlignment(
    String columnName,
    SemanticTarget target,
    double confidence,
    List<AlignmentSignal> signals
) {}

public record SemanticTarget(String ontology, String resourceType, String fieldName) {}

public enum AlignmentSignalType {
    NAME_SIMILARITY, TYPE_MATCH, VALUE_OVERLAP, PROFILE_MATCH, TAG_MATCH
}

public record AlignmentSignal(
    AlignmentSignalType type,
    double score,
    String description
) {}

public record NewTypeSuggestion(
    String proposedTypeName,
    List<String> columns,
    double confidence,
    String rationale
) {}
```

5 种对齐信号（见 `AlignmentSignalType` enum），由 `AlignmentInference` 规则实现：

```java
// heirloom-server/.../discovery/inference/rules/
public class AlignmentInference implements InferenceRule {
    private final AlignmentService alignmentService;

    public AlignmentInference(AlignmentService alignmentService) {
        this.alignmentService = alignmentService;
    }

    @Override
    public List<ResourceTypeProposal> infer(InferenceContext ctx) {
        if (ctx.alignment() == null) return List.of();
        // 将 AlignmentMap 中的高置信度对齐转为 ResourceTypeProposal 字段建议
        ...
    }
}
```

### 5.4 新管线顺序

```
TypeName → FieldMapper → Relationship → Description → Alignment → Ability → StateMachine
```

---

## 6. API 层

### 6.1 新增端点

| Controller | 路由 | 功能 |
|-----------|------|------|
| `ColumnResource` | `GET /v1/tables/{fqn}/columns` | 列查询（可按 Tag 过滤） |
| `ClassificationResource` | `/v1/classifications` | 分类 CRUD |
| `TagResource` | `/v1/tags` | 标签 CRUD + 列打标 |
| `ProfilingResource` | `/v1/profiling/tables/{fqn}` | 触发剖析 + 查询报告 |
| `AlignmentResource` | `/v1/alignment/tables/{fqn}` | 触发对齐 + 查询结果 |

### 6.2 现有端点增强

- `DiscoveryResource`：`POST /v1/discovery/sources/{fqn}/run?profile=true` 启动发现→剖析→对齐→推断全流程
- `TableResource`：响应新增 `tags, domainFQN, constraints, sourceHash, lifecycle, certification`
- `TypeResource`：InferencePipeline 调用时传入 `InferenceContext`

### 6.3 优先级

| 优先级 | 端点 | 理由 |
|--------|------|------|
| P0 | Table 增强 + Column 查询 | 元数据层基础可见性 |
| P0 | Classification + Tag CRUD | Profiling 和 Perspective 的前置依赖 |
| P1 | Profiling 触发 + 查询 | 核心数据建设能力 |
| P1 | Discovery + Profiling 联动 | 端到端管线验证 |
| P2 | Alignment | 依赖 Profiling 完成 + 已有 ResourceType |

---

## 7. Flyway 迁移

| 版本 | 文件 | 内容 |
|------|------|------|
| V17 | `enhance_metadata_tables.sql` | `metadata_tables` 新增 `tags, domain_fqn, constraints, source_hash, lifecycle, certification` |
| V18 | `create_classifications_tags.sql` | 新建 `metadata_classifications` + `metadata_tags` 表 |
| V19 | `add_domain_parent.sql` | `metadata_domains` 新增 `parent_fqn` |
| V20 | `create_column_profiles.sql` | 新建 `column_profiles` 表 + 索引 |
| V21 | `enhance_table_profiles.sql` | `table_profiles` 新增 `profiled_at, profiling_duration_ms, null_count, distinct_count, duplicate_row_count` |

所有 `ALTER TABLE ... ADD COLUMN` 带 `DEFAULT`，对现有数据零影响。

`metadata_tables.columns` JSONB 中已存在的数据不做 DDL 迁移。Java 层提供兼容解析：`ColumnDefParser` 先尝试按 `ColumnDef` 结构反序列化，失败则回退到旧格式（`List<Map<String,Object>>`）并按原名/类型映射为 `ColumnDef`（tags/comment/defaultValue/nullable 等新字段置默认值）。新写入始终用 `ColumnDef` 格式。旧数据随 Discovery 重新扫描自然更新。

---

## 8. 测试策略

| 层级 | 范围 | 工具 |
|------|------|------|
| 单元测试 | `heirloom-core` 接口契约 + InferencePipeline 增强分支 | JUnit 5 + Mockito |
| Repository 测试 | 新 JPA Repository CRUD | `@DataJpaTest` + H2 |
| 集成测试 | Profiling 端到端、Tag 打标流程、Discovery 联动 | Testcontainers + PostgreSQL |
| 回归 | 现有 192 测试全部通过 | 全量 `mvn test` |

---

## 9. 实施计划

### Phase 0 — 模块化（1.5–2 周）

0.1 创建 `heirloom-core` Maven 模块（零外部依赖）
0.2 迁移 `HeirloomEntity`, `EntityRegistry`, `EntityRegistration`
0.3 迁移 `RawSchema`, `RawTable`, `RawColumn`, `DiscoveryConfig`
0.4 迁移 `SchemaExtractor` SPI + 新建 `SchemaExtractorRegistry`
0.5 迁移 `SemanticQuery`, `QueryParser`, `AggregationQuery`
0.6 创建 `heirloom-connector-postgres`，移入 `PostgresSchemaExtractor`
0.7 创建 `heirloom-connector-mysql`，移入 `MySqlSchemaExtractor`
0.8 `heirloom-server` 改为组合层

**退出标准**：ArchUnit 验证 `heirloom-core` 不依赖 Spring Boot 和 connector。192 测试通过。

### Phase 1 — 元数据实体补齐（1.5–2 周）

1.1 `heirloom-core` 定义 Classification/Tag/Domain 接口
1.2 Flyway V17+V18+V19
1.3 `heirloom-server` 实现 JPA Entity + Repository
1.4 Domain 加 parentFQN + 层次查询
1.5 TableEntity 加 6 新字段
1.6 ColumnProfileEntity + Repository（V20）

**退出标准**：四表联查可用。TableEntity 新字段读写通过。

### Phase 2 — Profiling 引擎（1.5–2 周）

2.1 `heirloom-core` 定义 ProfilingService 接口 + record
2.2 JDBC-based ProfilingServiceImpl
2.3 质量评分计算
2.4 TableProfileEntity 增强（V20）
2.5 DiscoveryService 插入 Profiling 步骤

**退出标准**：`POST /v1/profiling/tables/{fqn}` 返回含 DataClass 推断的列级报告。

### Phase 3 — InferencePipeline + Alignment（1.5–2 周）

3.1 InferenceContext + InferencePipeline 接口更新
3.2 6 条规则加 Profiling 增强分支
3.3 AlignmentService 接口 + record
3.4 AlignmentInference 实现（5 种信号）
3.5 接入 InferencePipeline 第 7 条规则
3.6 Discovery 全流程：Extract → Metadata → Lineage → Profile → Align → Infer → Propose

**退出标准**：端到端管线产出含对齐建议的 ResourceType Proposal。

### Phase 4 — API + Workshop（1–1.5 周）

4.1 ClassificationResource + TagResource
4.2 ColumnResource（查询 + 按 Tag 过滤）
4.3 ProfilingResource + AlignmentResource
4.4 TableResource 响应增强
4.5 Workshop 面板

**总工期：6–8 周**

---

## 10. 对比 OpenMetadata

详见 `docs/references/heirloom-vs-openmetadata.md`。本设计的关键取舍：

| 做 | 不做 |
|----|------|
| Column 内嵌 JSONB | 独立 Column 表 |
| Classification + Tag 两层 | AutoClassification ML 引擎 |
| JDBC 直查 Profiling | Python 摄入框架 |
| 5 维质量评分 | TestSuite 独立产品 |
| Alignment（Heirloom 独有） | DataProduct / DataContract |
| 7 条规则 InferencePipeline | Dashboard/Pipeline 等 33 种资产 |
| pgvector + tsvector 搜索 | Elasticsearch/OpenSearch |
| 1 种资产类型（Table） | 33 种 |
| ~18 个实体 | 80+ 个 |

---

## 11. 与 ADR-041 的接口差异

ADR-041 定义了完整的 ProfilingService、AlignmentService、EntityResolutionService 接口契约。本设计提前了 Profiling 和 Alignment，推迟了 Entity Resolution。在纳入过程中对接口做了以下裁剪（均为 MVP 范围简化，非架构否定）：

| ADR-041 字段 | 本设计 | 理由 |
|-------------|--------|------|
| `ProfilingService.profile(tenantId, tableFQN)` | 去掉 `tenantId` 参数 | 多租户在 Phase 7，MVP 单租户无此概念 |
| `ColumnProfile.suspectedClasses: Set<DataClass>` | `detectedClass: DataClass`（单值） | 简化实现。优先级匹配（见 4.3）已保证单值推断。未来可恢复为 Set |
| `DataClass` 含 UUID, CREDIT_CARD, SSN | 去掉（仅保留 8 个值） | 规则推断无法覆盖且非语义层刚需 |
| `AlignmentRequest.tenantId` | 去掉 | 同上，无多租户 |
| `AlignmentMap.tenantId`, `alignedAt` | 去掉 `tenantId`，`alignedAt` 改用 `ProfilingService` 调用时间戳 | 简化 |
| `FieldAlignment.columnDataType` | 去掉 | 数据类型可从 Profiling 结果或 RawColumn 获取，不重复存储 |
| `SemanticTarget.fieldDataType` | 去掉 | 同上，`fieldDataType` 从已有 ResourceType 的 Field 定义查询 |
| `AlignmentSignal.type` (String) | 改为 `AlignmentSignalType` enum | 类型安全——5 种信号是固定集合，不应接受任意字符串 |
| `AlignmentSignal` 含 `glossary_match` | `TAG_MATCH` 替换 `glossary_match` | Heirloom 不做独立术语表（推迟），用 Tag 体系替代术语概念 |
| `EntityResolutionService` | 推迟到 Phase 6 | ER 需要 Profiling + Alignment 就绪后再做 |

> **ADR-036 范围扩展**：ADR-036 原定只迁移 Postgres Connector，MySQL 暂留 `heirloom-server`。本设计 Phase 0 同步迁移两个 Connector（0.6 postgres + 0.7 mysql）。理由是两具 Connector 代码量相近（各约 200 行），且当前 MySQL Connector 仅为骨架实现——同时迁出可避免 Connector SPI 接口稳定后二次迁移。

---

## 12. ADR 更新

本规格**取代 ADR-045**（实现优先级与 MVP 范围）。见 [ADR-046](../adr/046-metadata-first-mvp.md)。

ADR-036（语义层模块化）保持有效，纳入 Phase 0。ADR-041（Profiling/Alignment/ER 接口契约）中的 Profiling 和 Alignment 纳入 Phase 2-3，ER 推迟。
