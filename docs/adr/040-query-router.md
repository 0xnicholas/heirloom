# ADR-040: Query Router — 语义查询与 raw 下钻的统一路由

## 状态

Proposed

## 日期

2026-07-08

## 上下文

随着 Heirloom 引入 DuckDB raw 数据分析层（ADR-037），系统中存在两类查询路径：

1. **语义查询**：基于 `ResourceType` 和 `MappingRule`，查询已建模的业务实体，受 Role/Capability/Perspective 约束。
2. **Raw 下钻查询**：直接对 DuckDB 中的 raw 数据做自由分析，支持复杂聚合、ad-hoc SQL、跨表 join。

用户通过统一的 Heirloom DSL 发起查询，系统需要决定：
- 这个查询应该走语义层还是 raw 层？
- 是否存在混合查询（部分语义 + 部分 raw）？
- 如何在不破坏安全边界的前提下执行 raw 查询？

## 决策

**在 API 层与执行层之间引入 `QueryRouter`，根据查询内容、元数据可用性和用户意图，将请求路由到 Semantic Layer 或 DuckDB raw store。**

### 架构位置

```
消费层（SDK / Workshop / MCP / REST / GraphQL）
        │
        ▼
   API Gateway / Controller
        │
        ▼
┌─────────────────┐
│   Query Router  │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
Semantic    DuckDB
 Layer      raw store
 (Query      (Raw
 Resolver)   Analyzer)
```

### 路由策略

| 策略 | 触发条件 | 执行路径 |
|------|---------|---------|
| **纯语义路由** | 查询只涉及已发布 `ResourceType` 和已映射字段 | Semantic Layer → Query Resolver → 底层数据源/Mapping Engine |
| **Raw 下钻路由** | 查询显式指定 raw table/column，或请求 `mode=raw`，或使用语义层无法表达的分析操作 | DuckDB raw store → 按需同步 → 执行 |
| **混合路由** | 查询以 Resource 为入口，但下钻到关联 raw 数据 | Semantic Layer 解析 Resource 定位 raw 表 → DuckDB 执行下钻 |
| **回退路由** | raw 查询目标未加载到 DuckDB，或 freshness 过期 | 触发 Ingestion，或根据配置回退到直连源，或报错 |

### Auto 模式决策树

当 `mode = AUTO` 时，Router 按以下顺序判断：

1. 查询显式包含 `rawTable` / `rawSql` → **Raw 下钻路由**
2. 查询包含 `resource` + `drillDown` → **混合路由**
3. 查询操作包含聚合、window、子查询、跨 raw 表 join 等语义层不支持的操作 → **Raw 下钻路由**
4. 查询 `type` 是已发布 Resource Type → **纯语义路由**
5. 否则 → 返回错误，提示用户显式指定 `mode`

### 路由判断逻辑

Query Router 基于以下信息做决策：

1. **查询中的类型/表引用**：
   - `type: "Customer"` → 语义层
   - `rawTable: "public.customers"` → raw 层
   - `resourceId` + `drillDown: { rawTable: ... }` → 混合

2. **操作类型**：
   - 标准 CRUD、过滤、排序、分页 → 语义层
   - 复杂聚合、window function、自定义 SQL、跨 raw 表 join → raw 层

3. **模式声明**：
   - 查询显式声明 `mode: "semantic" | "raw" | "hybrid"`

4. **元数据可用性**：
   - 目标 Resource Type 是否存在且 ACTIVE
   - 所需 raw 表是否已在 DuckDB 中存在且未过期

5. **权限边界**：
   - 无论路由到哪一层，先经过 Role/Capability 校验
   - Semantic Layer 用 `PerspectiveEngine` 裁剪字段
   - Raw 层用额外的 `RawQueryAuthorizer` 限制可访问表/列（基于 Role 配置）

### 接口契约

Query Router 只负责**路由决策**，具体执行由对应引擎负责。`QueryRouter`、`RouteDecision`、`QueryRequest`、`QueryMode` 等接口与模型位于 `heirloom-core`；`SemanticExecutor`、`DuckDbExecutor` 等具体执行器位于 `heirloom-server` 或对应存储模块。

```java
public interface QueryRouter {
    RouteDecision decide(QueryRequest request, CallerContext caller);
}

public interface QueryExecutor {
    QueryResult execute(RouteDecision decision);
}

public class QueryRequest {
    private String tenantId;
    private QueryPayload payload;      // JSON DSL 或 SQL 字符串
    private QueryMode mode;            // AUTO / SEMANTIC / RAW / HYBRID
    private Map<String, Object> context;
}

public sealed interface QueryPayload {
    record JsonDsl(Object dsl) implements QueryPayload {}
    record RawSql(String sql) implements QueryPayload {}
}

public enum QueryMode {
    AUTO,      // 由 Router 自动判断
    SEMANTIC,  // 强制走语义层
    RAW,       // 强制走 raw 层
    HYBRID     // 语义定位 + raw 下钻
}

public class RouteDecision {
    private QueryMode mode;
    private List<RouteStep> steps;     // 每步有独立 engine 和权限上下文
}

public class RouteStep {
    private String engine;             // "semantic" / "duckdb"
    private QueryPayload payload;      // 该步实际执行的查询
    private Set<String> authorizedTables;
    private Set<String> authorizedColumns;
    private Map<String, Object> bindings;  // 混合查询时上下游参数绑定
}
```

### 混合查询执行示例

请求：查询 `Customer#123` 的所有 raw 订单记录，并按金额聚合。

```json
{
  "mode": "hybrid",
  "resource": { "type": "Customer", "rid": "customer-123" },
  "drillDown": {
    "rawTable": "prod.pg.orders_db.public.orders",
    "filter": { "customer_id": "__bind:customer_id" },
    "aggregate": { "function": "sum", "field": "amount", "groupBy": ["status"] }
  }
}
```

`RouteDecision` 生成两步：

| Step | Engine | Payload | 说明 |
|------|--------|---------|------|
| 1 | semantic | `{ "type": "Customer", "rid": "customer-123", "fields": ["customer_id"] }` | 解析 Resource 获取绑定值 |
| 2 | duckdb | raw SQL：`SELECT status, SUM(amount) FROM ... WHERE customer_id = ?` | 使用 step1 的 `customer_id` 执行 |

执行流程：

1. Query Router 生成两步 `RouteDecision`。
2. `SemanticExecutor` 执行 step1，返回 `{ "customer_id": "C-456" }`。
3. `DuckDbExecutor` 执行 step2，注入绑定值，检查 `orders` 表 freshness，过期则触发 Ingestion。
4. 返回结果，受 `RawQueryAuthorizer` 字段级权限控制。

### 安全边界

| 层级 | 安全机制 |
|------|---------|
| 入口 | 校验调用者身份；确认其 Role 包含 raw 查询或语义查询的权限 |
| 语义层 | `CapabilityResolver` + `PerspectiveEngine` 按 Role 裁剪字段；`ActionPipeline` 控制写入 |
| Raw 层 | `RawQueryAuthorizer` 基于 Role 的 `rawQueryRestrictions` 配置限制可访问表/列；禁止 DDL、写入操作 |
| 混合查询 | 语义部分走 Capability/Perspective，raw 部分走 `RawQueryAuthorizer`，两者各自校验 |

#### Raw 层授权模型

Semantic Layer 的 Capability 基于 Resource Type 的 Abilities（`query` / `mutate` 等），而 raw 表本身不是 Resource。因此需要独立的 raw 查询授权配置：

```java
public class RawQueryRestrictions {
    private Set<String> allowedTables;     // 允许访问的 raw table FQN 列表
    private Set<String> deniedTables;      // 显式拒绝的表
    private Set<String> allowedColumns;    // 列白名单（可选）
    private boolean allowAggregation;      // 是否允许聚合
    private boolean allowJoin;             // 是否允许跨表 join
    private int maxRows;                   // 单次查询返回上限
}
```

`RawQueryAuthorizer` 在路由后、执行前校验：
1. 查询引用的所有表/列是否在允许列表内；
2. 是否包含聚合/join 等受限操作；
3. 是否包含 DDL/DML（一律拒绝）。

推荐基于 JSqlParser 实现 SQL 解析与校验：在执行前将 SQL 解析为 AST，提取真实表/列引用和操作流程，避免正则扫描被绕过。DuckDB 原生解析可作为未来支持 DuckDB 专属语法时的 fallback。

Raw 层默认只读。任何写入 DuckDB 的操作（如同步、刷新）由 Ingestion Service 内部执行，不暴露给用户查询接口。

### 结果缓存

Query Router 不强制缓存查询结果，但可配置短时效缓存：

- **语义查询**：由 Semantic Layer 的现有缓存策略控制（如 `PerspectiveEngine` 的 Role→字段映射缓存）。
- **Raw 查询**：可配置按 `(tenant, rawTable, queryHash, freshness)` 缓存结果，减少重复分析负载。
- **混合查询**：缓存 key 需包含语义 step 的绑定值，避免不同 Resource 实例共享错误结果。

## 后果

**积极**：
- 用户通过统一 DSL 访问语义和 raw 两层，体验一致。
- 语义层和 raw 层可以独立演进，Router 隔离差异。
- 混合查询让“先理解业务实体，再下钻分析”的 workflow 自然成立。
- 安全模型可分层实施：语义层用现有 Capability，raw 层用额外授权配置。

**消极**：
- Query Router 需要理解两种查询语义，本身成为复杂组件。
- 混合查询的执行计划、错误处理、性能优化比单一引擎更难。
- raw 查询不经过 `ResourceType` 语义约束，可能因 raw 表 schema 差异导致结果与语义层不一致（如同一概念在不同表中有不同命名或类型）。
- 需要维护 `RawQueryRestrictions` 与 Semantic Layer Role 配置的同步，否则权限边界可能出现缝隙。
- 混合查询的绑定参数传递和错误定位需要额外设计（如 step1 失败时 step2 不应执行）。

## 备选方案

### 方案 A：两个独立查询端点

提供 `/v1/query/semantic` 和 `/v1/query/raw` 两个独立端点，由客户端决定。

**放弃理由**：
- 混合查询需要在客户端做两次调用并自行关联，体验差。
- 客户端需要理解底层架构，违背 Semantic Layer 简化访问的初衷。

### 方案 B：所有查询都走 DuckDB

DuckDB 中不仅存 raw 数据，也物化 Resource 视图，所有查询统一走 DuckDB。

**放弃理由**：
- 需要把 Heirloom 的权限、Perspective、状态机等语义逻辑下沉到 DuckDB，破坏语义层作为唯一安全边界的原则。
- 与 ADR-001 语义中枢模式冲突。

### 方案 C：所有查询都走 Semantic Layer

拒绝 raw 下钻，只扩展 JSON DSL 的分析能力。

**放弃理由**：
- 无法满足数据分析师自由探索 raw 数据的需求。
- 在 JSON DSL 中表达复杂 SQL 等价物会导致 DSL 过度膨胀。

## 相关 ADR

- [ADR-001](./001-semantic-core-as-hub.md) — 语义中枢
- [ADR-004](./004-json-dsl-query-language.md) — JSON DSL 查询语言
- [ADR-009](./009-perspective-engine-placement.md) — Perspective Engine
- [ADR-036](./036-semantic-layer-modularization.md) — Semantic Layer 模块化
- [ADR-037](./037-duckdb-raw-store.md) — DuckDB raw store
- [ADR-038](./038-raw-to-ontology-pipeline.md) — raw 到 ontology 事件驱动管线
