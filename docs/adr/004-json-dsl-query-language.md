# ADR-004: JSON DSL 作为查询语言

## 状态
Accepted

## 日期
2026-05-28

## 上下文

Heirloom 的 Query Resolver 需要一种查询语言，供人类用户和 AI Agent 表达语义查询（过滤、路径遍历、聚合、语义搜索）。

我们评估了四种候选方案：

| 方案 | 示例 |
|------|------|
| SQL（通用查询语言）| `SELECT c.name, o.total FROM customer c JOIN order o ON ...` |
| Cypher（图查询语言）| `MATCH (c:Customer)-[:placed]->(o:Order) WHERE ...` |
| GraphQL（API 查询语言）| `{ customer { name orders { total } } }` |
| JSON DSL（结构化查询）| `{"from":"Customer","traverse":[{"path":"...","filter":{}}]}` |

关键非功能性需求：查询语言需要**被 LLM 可靠生成**（AI Agent 场景的核心约束）。这排除了自由文本格式（SQL、Cypher）——因为 LLM 容易产生语法错误、表名幻觉和注入风险。

## 决策

**采用结构化 JSON DSL 作为查询语言。**

核心设计：
- `from` + `filter` + `select` + `limit` 构成基础查询
- `traverse` 数组支持嵌套路径遍历，每条路径用图查询语法（`c --[placed]--> Order`）
- `aggregate` 支持分组聚合
- `search` 支持向量+关键词混合语义搜索

路径表达式语法：`alias --[relation]--> TargetType as alias`。支持正向、反向、通配和深度控制（`-->1..2`）。

## 后果

**积极**：
- **LLM-friendly**：结构化格式比自由文本更容易被 LLM 准确生成。JSON 结构天然可做 schema 校验（不符合 DSL schema 的查询在到达 Query Resolver 之前就被拒绝）
- **注入风险低**：不存在 SQL 注入或 Cypher 注入等字符串拼接风险——因为查询是结构化对象，不是字符串拼接
- **易于组合和程序化构建**：无需字符串模板或查询构建器

**消极**：
- **非图灵完备**：DSL 不支持复杂条件逻辑（如子查询、CASE WHEN、递归遍历），复杂分析场景需 Falls back 到 BI 工具直连底层存储
- **学习成本**：对习惯 SQL 的工程师有额外的切换成本
- **DSL 维护负担**：随着查询需求增长，DSL 需要持续扩展新的操作符和语法——存在语言膨胀风险

## 备选方案

### 方案 A：直接使用 SQL
用户和 Agent 写 SQL 查询，Query Resolver 直接转发或翻译为多数据源 SQL。

**放弃理由**：SQL 对 LLM 生成极不友好——表名和列名的幻觉问题严重，且 SQL 注入在自然语言生成的 SQL 中几乎无法防止。此外，SQL 的表达力以关系代数为基础，表达图遍历（多跳路径）需要递归 CTE，可读性差。

### 方案 B：直接使用 Cypher / openCypher
使用图查询语言。

**放弃理由**：同样存在 LLM 生成质量问题，且 Cypher 的聚合和过滤能力不如我们所需的灵活。此外，Cypher 对非图存储后端（如 Resource Store 的属性查询）无原生支持。

### 方案 C：直接使用 GraphQL
利用 GraphQL 的嵌套查询语义。

**放弃理由**：GraphQL 的嵌套模型天然适合关系遍历，但 GraphQL 缺少聚合、语义搜索和复杂的过滤表达式支持。需要大量自定义 directive 和扩展，最终失去「标准 GraphQL」的互操作优势。

## 相关 ADR

- [ADR-001](./001-semantic-core-as-hub.md) — Query Resolver 在语义中枢中的位置
