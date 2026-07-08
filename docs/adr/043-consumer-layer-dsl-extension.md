# ADR-043: 消费层与 DSL 扩展

## 状态

Proposed

## 日期

2026-07-08

## 上下文

Heirloom 的消费层包括：

- **heirloom-sdk**：Python SDK，供 AI Agent 和脚本调用。
- **heirloom-mcp**：MCP Server，把 Heirloom 能力暴露给 MCP 客户端（如 Claude Desktop、Cursor）。
- **workshop**：React 工作台，供数据工程师和业务用户可视化建模、查询、治理。
- **REST API**：通用 HTTP 接口（本 ADR 的主要入口）。
- **GraphQL**：现有查询接口（作为补充，复用 Query Router）。

随着 Query Router（ADR-040）引入语义查询和 raw 下钻两种路径，消费层需要：

1. 统一调用 Query Router，无需关心底层是语义层还是 DuckDB。
2. 扩展 DSL 以表达 raw 下钻、混合查询、分析操作。
3. 保持 SDK/MCP/Workshop 的接口一致性。

## 决策

**消费层通过统一的 Query API 访问 Heirloom，使用扩展后的 Heirloom DSL 表达语义查询、raw 下钻和混合查询。**

### 消费层与 Query Router 的关系

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ heirloom-sdk │   │ heirloom-mcp │   │   workshop   │
└───────┬──────┘   └───────┬──────┘   └───────┬──────┘
        │                  │                  │
        └──────────────────┼──────────────────┘
                           ▼
                   POST /v1/query
                   X-Tenant-Id: {tenantId}
                           │
                           ▼
                     Query Router
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
       Semantic Layer              DuckDB raw store
```

所有消费层最终都调用 `POST /v1/query`，通过 HTTP Header `X-Tenant-Id` 传递租户，通过 DSL 中的 `mode` 字段声明查询意图。

### DSL 版本

DSL 新增 `version` 字段用于向后兼容：

```json
{
  "version": "2026-07",
  "mode": "raw",
  ...
}
```

- 不指定 `version` 时默认使用最新稳定版本。
- 破坏性变更通过新增 `version` 值引入，旧版本 consumer 仍可工作。

### DSL 扩展

Heirloom DSL 在原有语义查询基础上增加 `raw` 和 `drillDown` 能力。

#### 1. 纯语义查询（与现有 DSL 兼容）

```json
{
  "mode": "semantic",
  "type": "Customer",
  "fields": ["id", "name", "email"],
  "filter": { "field": "tier", "op": "$eq", "value": "gold" },
  "limit": 100
}
```

#### 2. Raw 下钻查询

```json
{
  "mode": "raw",
  "rawTable": "prod.pg.orders_db.public.orders",
  "fields": ["order_id", "customer_id", "amount", "status"],
  "filter": { "$and": [
    { "field": "status", "op": "$eq", "value": "completed" },
    { "field": "amount", "op": "$gt", "value": 1000 }
  ]},
  "aggregate": { "function": "$sum", "field": "amount", "groupBy": ["status"] },
  "limit": 1000
}
```

#### 3. 混合查询

```json
{
  "mode": "hybrid",
  "resource": { "type": "Customer", "rid": "customer-123" },
  "drillDown": {
    "rawTable": "prod.pg.orders_db.public.orders",
    "bindings": { "customer_id": "__resource.customer_id" },
    "fields": ["order_id", "amount", "status"],
    "aggregate": { "function": "$sum", "field": "amount", "groupBy": ["status"] }
  }
}
```

#### 4. 原始 SQL 模式（受限）

```json
{
  "mode": "raw",
  "sql": "SELECT status, SUM(amount) FROM raw.orders WHERE customer_id = ? GROUP BY status",
  "parameters": ["C-456"]
}
```

> 原始 SQL 必须受 `RawQueryAuthorizer` 校验：禁止 DDL/DML，限制表/列范围。DuckDB 中 raw 表默认映射到 `raw` schema，具体表名校验由后端完成。

### DSL 字段说明

| 字段 | 语义查询 | Raw 查询 | 混合查询 | 说明 |
|------|---------|---------|---------|------|
| `mode` | `semantic` | `raw` | `hybrid` | 路由模式 |
| `type` | ✅ | ❌ | ✅（resource.type） | Resource Type |
| `rid` / `filter` | ✅ | ❌ | ✅（resource.rid/filter） | 定位 Resource |
| `rawTable` | ❌ | ✅ | ✅（drillDown.rawTable） | Raw 表 FQN |
| `sql` | ❌ | ✅ | ❌ | 原始 SQL |
| `fields` | ✅ | ✅ | ✅ | 返回列 |
| `filter` | ✅ | ✅ | ✅ | 过滤条件 |
| `aggregate` | ❌ | ✅ | ✅ | 聚合 |
| `drillDown` | ❌ | ❌ | ✅ | 下钻配置 |
| `bindings` | ❌ | ❌ | ✅ | 语义→raw 参数绑定 |

### 消费层封装

#### Python SDK

```python
# 语义查询
customers = heirloom.semantic_query(
    type="Customer",
    fields=["id", "name", "email"],
    filter={"field": "tier", "op": "$eq", "value": "gold"}
)

# Raw 下钻
orders = heirloom.raw_query(
    raw_table="prod.pg.orders_db.public.orders",
    fields=["order_id", "amount"],
    aggregate={"function": "$sum", "field": "amount", "groupBy": ["status"]}
)

# 混合查询
orders = heirloom.hybrid_query(
    resource={"type": "Customer", "rid": "customer-123"},
    drill_down={
        "raw_table": "prod.pg.orders_db.public.orders",
        "bindings": {"customer_id": "__resource.customer_id"},
        "aggregate": {"function": "$sum", "field": "amount", "groupBy": ["status"]}
    }
)
```

#### MCP Server

暴露以下 tools：

| Tool | 说明 | MVP 阶段 |
|------|------|---------|
| `heirloom_query` | 语义查询 | ✅ |
| `heirloom_describe_resource_type` | 获取 Resource Type 定义 | ✅ |
| `heirloom_raw_query` | Raw 下钻查询 | 增强阶段（依赖 raw store 稳定） |
| `heirloom_hybrid_query` | 混合查询 | 增强阶段 |
| `heirloom_ingest` | 触发 raw 数据同步 | 增强阶段（MVP 通过 REST/API 触发） |

#### Workshop

- **Schema 视图**：展示 Resource Type、raw 表、Mapping Rule。
- **Query Builder**：支持切换 Semantic / Raw / Hybrid 模式，可视化构建 DSL。
- **结果表格**：统一展示查询结果，标注每列来源（语义字段 / raw 列）。

#### GraphQL 集成

GraphQL 查询通过新增 `query` root field 复用 Query Router：

```graphql
query {
  query(input: {
    version: "2026-07",
    mode: RAW,
    rawTable: "prod.pg.orders_db.public.orders",
    fields: ["status", "totalAmount"],
    aggregate: { function: SUM, field: "amount", groupBy: ["status"] }
  }) {
    rows
    meta { freshness { syncedAt } warnings }
  }
}
```

GraphQL schema 中的 `QueryInput` 使用输入类型（Input Type）封装 DSL，结果以 JSON scalar 返回，保持 schema 简洁。

### 返回格式

所有查询返回统一格式：

```json
{
  "mode": "semantic",
  "executedBy": "semantic",
  "rows": [
    { "id": "customer-123", "name": "Acme", "email": "acme@example.com" }
  ],
  "meta": {
    "totalCount": 1,
    "limit": 100,
    "offset": 0,
    "freshness": null,
    "warnings": []
  }
}
```

Raw/Hybrid 查询额外返回：

```json
{
  "meta": {
    "freshness": {
      "rawTable": "prod.pg.orders_db.public.orders",
      "syncedAt": "2026-07-08T08:00:00Z",
      "sourceMaxTs": "2026-07-08T07:55:00Z"
    },
    "warnings": ["Result truncated to 1000 rows"]
  }
}
```

### 异步执行与大结果集

复杂 raw 查询可能执行时间较长，支持异步模式：

```json
{
  "mode": "raw",
  "rawTable": "prod.pg.orders_db.public.orders",
  "async": true,
  "callbackUrl": "https://..."
}
```

异步流程：

1. 服务端返回 `queryJobId`。
2. 查询在后台执行，完成后通过 `callbackUrl` 通知调用方。
3. 调用方通过 `GET /v1/query/jobs/{queryJobId}` 获取结果或状态。

同步查询默认限制返回行数（如 10,000），超限时返回截断警告和 `nextCursor`，调用方通过 cursor 分页获取后续数据。

### 错误格式

统一错误响应：

```json
{
  "error": {
    "code": "RAW_QUERY_UNAUTHORIZED",
    "message": "Table 'raw.orders' not allowed for role 'data_analyst'",
    "details": {
      "table": "raw.orders",
      "role": "data_analyst"
    }
  }
}
```

## 后果

**积极**：
- 消费层接口统一，不同客户端复用同一后端逻辑。
- DSL 扩展保持了与现有语义查询的兼容性。
- 用户/Agent 可以在同一接口内从语义理解平滑过渡到 raw 分析。
- MCP Server 让 LLM Agent 能够自然调用 Heirloom 的查询能力。

**消极**：
- DSL 变得更复杂，需要更完善的文档和 SDK 封装。
- 原始 SQL 模式引入潜在注入风险，需要严格校验。
- Workshop 需要支持三种查询模式的 UI，开发成本增加。
- 混合查询的调试和错误定位比单一模式更难。

## 备选方案

### 方案 A：消费层直接调用不同后端 API

SDK/MCP/Workshop 分别调用 `/v1/query/semantic` 和 `/v1/query/raw`。

**放弃理由**：
- 与 ADR-040 的 Query Router 设计冲突。
- 客户端需要理解路由逻辑，增加复杂度。

### 方案 B：完全用自然语言替代 DSL

LLM 把自然语言翻译成内部查询，消费层只暴露自然语言接口。

**放弃理由**：
- 当前阶段 LLM 翻译不可靠，复杂分析查询容易出错。
- 数据工程师需要精确控制查询，DSL 更可控。
- 自然语言可作为 Workshop/MCP 的可选功能，而非唯一接口。

### 方案 C：GraphQL 作为唯一消费接口

所有查询通过 GraphQL，语义和 raw 分别用不同 schema。

**放弃理由**：
- Heirloom 当前已有 REST DSL，GraphQL 可作为补充而非替代。
- Raw 下钻的动态 schema 与 GraphQL 强类型模型冲突较大。

## 相关 ADR

- [ADR-004](./004-json-dsl-query-language.md) — JSON DSL 查询语言
- [ADR-035](./035-agent-sdk-perspective.md) — Agent SDK & Perspective Engine Integration
- [ADR-040](./040-query-router.md) — Query Router
