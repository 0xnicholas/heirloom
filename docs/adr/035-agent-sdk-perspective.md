# ADR-035: Agent SDK & Perspective Engine Integration

**关联文档**: [ADR-032 知识库架构](./032-knowledge-base-architecture.md)、[ADR-034 图谱与治理](./034-knowledge-graph-lifecycle.md)
**状态**: Proposed
**日期**: 2026-06-22

---

## Part 1: Agent SDK — 知识消费接口

### 1. 设计原则

Agent 消费知识的方式不同于人类浏览网页。三个核心差异驱动了 SDK 设计：

| 人类 | Agent |
|------|-------|
| 浏览目录、搜索关键词 | 需要**结构化上下文**注入已有决策流程 |
| 容忍不相关结果（扫一眼跳过） | 上下文窗口有限，**精确度 ≥ 召回率** |
| 自己判断信息来源可信度 | 需要**明确引用链**来防止幻觉 |

### 2. SDK 方法表面

```
heirloom.knowledge.
  ├── search(query, filters)          → SearchResult[]    # 全文/语义搜索
  ├── getContext(fqn)                 → KnowledgeContext   # 单条目完整上下文
  ├── traverse(from, depth, dir)      → Graph              # 图遍历探索
  ├── getPrerequisites(actionFqn)     → KnowledgeContext[] # 执行 Action 前须知
  ├── getRelated(entityFqn, limit)    → KnowledgeContext[] # 与实体相关的知识
  ├── summarize(fqn, maxTokens)       → Summary            # LLM 摘要
  └── list(filters)                   → KnowledgeArticle[] # 列表浏览
```

### 3. 方法详细设计

#### 3.1 search — 语义搜索

```python
# Agent SDK (Python)
results = heirloom.knowledge.search(
    query="how to handle freshness alert on orders pipeline",
    mode="hybrid",                    # "fts" | "vector" | "hybrid"
    type=["Playbook", "Metric"],      # 过滤知识类型
    domain="engineering",             # 过滤领域
    min_confidence=0.6,               # 语义搜索最低相似度
    limit=5
)

# Response: List[SearchResult]
# SearchResult {
#   article_fqn: "knowledge.primary.playbooks.incident-response"
#   title: "Incident Response — Freshness Alert"
#   snippet: "The freshness alert fires when `orders` falls more than 30 minutes..."
#   score: 0.87
#   highlights: ["<b>freshness alert</b>", "<b>orders pipeline</b>"]
#   references: ["metadata_tables.sales.orders"]
# }
```

**与人类搜索 API 的区别**：
- Agent 搜索默认使用 `hybrid` 模式（人类默认 FTS）
- Agent 搜索结果包含 `references` 字段——Agent 可以据此串联查询元数据实体
- `min_confidence` 阈值防止低质量结果浪费上下文窗口

#### 3.2 getContext — 结构化上下文

Agent 执行任务时需要的不是一篇文档全文，而是**多篇文档中提取的结构化上下文**。

```python
# Agent 在执行 update_tier Action 前获取上下文
context = heirloom.knowledge.getContext(
    fqn="knowledge.primary.tables.customers"
)

# Response: KnowledgeContext（为 LLM 优化的结构化格式）
# {
#   "article": {
#     "title": "Customers — Master Data",
#     "type": "BigQuery Table",
#     "sections": {
#       "Schema": "| Column | Type | Description | ...",
#       "Usage Notes": "This table is the source of truth for all customer data...",
#       "Business Rules": "A customer is considered 'active' if they have at least one order in 30 days"
#     },
#     "resource": "@metadata_tables.crm.customers",
#     "last_updated": "2026-06-15"
#   },
#   "related_knowledge": [              // 自动包含一跳引用
#     {
#       "title": "Customer Tier Update Policy",
#       "relationship": "referenced by current article",
#       "snippet": "Tier updates must be approved by...",
#       "fqn": "knowledge.primary.policies.tier-update"
#     }
#   ],
#   "referenced_entities": [            // 元数据实体摘要
#     {
#       "fqn": "metadata_tables.crm.customers",
#       "type": "table",
#       "columns": 12,
#       "lineage_count": 3,
#       "quality": {"freshness": "green", "nulls_pct": 0.02}
#     }
#   ],
#   "citations": [                      // 支持 Agent 引证
#     {"source": "knowledge.primary.tables.customers", "section": "Business Rules"},
#     {"source": "BigQuery table schema", "url": "https://console.cloud.google.com/..."}
#   ]
# }
```

#### 3.3 traverse — 图探索

```python
# Agent 探索：「customer 这个概念周围还有什么？」
graph = heirloom.knowledge.traverse(
    from_fqn="metadata_tables.crm.customers",
    direction="both",                 # incoming + outgoing
    edge_types=["ENTITY_REF"],        # 只看知识→实体的边
    max_depth=2,
    limit=20
)

# Response: Graph
# {
#   "nodes": [
#     {"fqn": "knowledge.primary.tables.customers", "type": "knowledge", "title": "Customers"},
#     {"fqn": "knowledge.primary.metrics.customer-ltv", "type": "knowledge", "title": "LTV 90d"},
#     {"fqn": "metadata_tables.crm.orders", "type": "entity", "name": "orders"}
#   ],
#   "edges": [...],
#   "suggestions": [                                   // 启发式推荐
#     "knowledge.primary.playbooks.customer-data-quality is 2 hops away",
#     "Consider adding knowledge for orphan entity: metadata_tables.crm.leads"
#   ]
# }
```

#### 3.4 getPrerequisites — 操作前须知

这是 Heirloom 知识库最有价值的 Agent 能力：**在执行写操作前，系统自动注入相关知识**。

```python
# SDK 方法
briefing = heirloom.knowledge.getPrerequisites(
    action_fqn="resourceType.Customer.update_tier"
)

# Response: PrerequisiteContext
# {
#   "action": {
#     "name": "update_tier",
#     "target_type": "Customer",
#     "gate": "user has Capability: customer.mutate"
#   },
#   "prerequisites": [
#     {
#       "priority": "required",                                  // required | recommended | informational
#       "knowledge_fqn": "knowledge.primary.policies.tier-update",
#       "title": "Customer Tier Update Policy",
#       "relevant_section": "## Approval Requirements\n\nTier changes from Silver→Gold require...",
#       "why_relevant": "This policy governs exactly the update_tier action you are about to execute"
#     },
#     {
#       "priority": "recommended",
#       "knowledge_fqn": "knowledge.primary.tables.customers",
#       "title": "Customers — Master Data",
#       "relevant_section": "## Schema\n\nThe `tier` field is an ENUM: Bronze|Silver|Gold|Platinum...",
#       "why_relevant": "Understanding the tier field schema prevents invalid updates"
#     }
#   ],
#   "total_relevant": 2,
#   "format_for_llm": "## Action Prerequisites\n\n..."   # 可直接注入 LLM 上下文的文本
# }
```

**匹配算法**：

```java
/**
 * 为给定 Action 查找相关知识。
 * 
 * 匹配策略（按优先级排序）：
 *   1. Action FQN 被知识条目直接引用     → priority = required
 *   2. Action 的 target 类型被引用        → priority = recommended
 *   3. target 类型的底层 Table 被引用     → priority = recommended
 *   4. 同 domain 的 Playbook 类型知识     → priority = informational
 */
public PrerequisiteContext getPrerequisites(String actionFqn) {
    Action action = actionRepo.findByFQN(actionFqn);
    String targetTypeFqn = action.getTargetTypeFqn();
    String targetTableFqn = resolveTableFqn(targetTypeFqn);
    String domain = extractDomain(targetTypeFqn);

    List<Prerequisite> results = new ArrayList<>();

    // Priority 1: Action directly referenced
    results.addAll(findDirectRefs(actionFqn, "required"));

    // Priority 2: Target type referenced
    results.addAll(findEntityRefs(targetTypeFqn, "recommended"));

    // Priority 3: Underlying table referenced
    if (targetTableFqn != null) {
        results.addAll(findEntityRefs(targetTableFqn, "recommended"));
    }

    // Priority 4: Same-domain playbooks
    results.addAll(findDomainPlaybooks(domain, "informational"));

    return buildContext(action, results);
}
```

#### 3.5 summarize — 上下文窗口优化

当知识条目正文很长时，Agent 的上下文窗口可能不够。SDK 提供 LLM 摘要能力：

```python
# 摘要整个知识条目
summary = heirloom.knowledge.summarize(
    fqn="knowledge.primary.tables.customers",
    max_tokens=500
)
# → "The customers table is the master data source for all customer records.
#    Key fields: customer_id (UUID PK), email (PII, masked in non-prod),
#    tier (ENUM: Bronze-Silver-Gold-Platinum), created_at (partition key).
#    Business rule: active customer = at least 1 order in 30 days.
#    Source: knowledge.primary.tables.customers.md, §Business Rules."

# 按章节摘要
section_summaries = heirloom.knowledge.summarize(
    fqn="knowledge.primary.tables.customers",
    sections=["Schema", "Business Rules"],
    max_tokens_per_section=200
)
```

### 4. 自动上下文注入模式

这是 SDK 的杀手级能力：**Agent 不需要主动搜索知识——SDK 在正确的时间自动注入正确的上下文**。

```python
# Agent 执行 Action 的标准模式
from heirloom import HeirloomAgent

agent = HeirloomAgent(role="data-analyst")

# Option 1: 显式注入（Agent 主动请求）
context = agent.knowledge.getPrerequisites("resourceType.Customer.update_tier")
result = agent.actions.execute("resourceType.Customer.update_tier", params, context)

# Option 2: 自动注入（SDK 拦截 execute，自动 fetch 并注入）
# agent.actions.execute() 内部自动调用 getPrerequisites()
# 并在 LLM prompt 中追加格式化的上下文
result = agent.actions.execute("resourceType.Customer.update_tier", params)
# ↑ SDK 自动完成：
#   1. getPrerequisites("resourceType.Customer.update_tier")
#   2. 格式化为 LLM 上下文
#   3. 追加到 execute prompt 中
#   4. 执行 Action（含完整上下文）

# Option 3: RAG 模式（Agent 自由对话）
response = agent.chat("What's the process for upgrading a customer tier?")
# ↑ SDK 内部：
#   1. knowledge.search("upgrading customer tier process")
#   2. 搜索结果作为 RAG 上下文注入 prompt
#   3. LLM 基于知识库内容回答
```

---

## Part 2: Perspective Engine 集成

### 5. 知识权限模型

知识条目需要参与 Heirloom 的统一授权体系。定义知识库专属的 Capability：

```java
// 新增到 EntityRegistry 或 Authorizer 的 Capability 枚举
public enum KnowledgeCapability {
    KNOWLEDGE_QUERY,    // 搜索和读取知识条目（最小权限）
    KNOWLEDGE_CREATE,   // 创建新知识条目（即编写 .md 文件——通过 Git，不通过 API）
    KNOWLEDGE_MANAGE,   // 管理知识源、触发同步、修改生命周期状态
    KNOWLEDGE_ADMIN      // 全部权限（包括删除知识源）
}
```

### 6. 三层过滤模型

知识权限在三个粒度上生效，在**查询阶段**过滤（非后处理）：

```
查询: GET /v1/knowledge/search?q=security+runbook
        │
        ▼
┌─────────────────────────────────────────────┐
│ Layer 1: Capability Gate                    │
│   无 KNOWLEDGE_QUERY → 403 Forbidden        │
│   有 KNOWLEDGE_QUERY → 继续                 │
└──────────────────┬──────────────────────────┘
                   ▼
┌─────────────────────────────────────────────┐
│ Layer 2: Domain Filter                      │
│   Role 被授予的 domain 列表 → WHERE domain IN (...) │
│   例: sales-analyst 只能看 domain=sales      │
└──────────────────┬──────────────────────────┘
                   ▼
┌─────────────────────────────────────────────┐
│ Layer 3: Type Filter                        │
│   Role 被授予的可读知识类型 → WHERE type IN (...) │
│   例: analyst 不能看 type=Security Playbook  │
│   例: agent 不能看 type=HR Policy            │
└──────────────────┬──────────────────────────┘
                   ▼
             返回过滤后的结果
```

### 7. 权限配置

```yaml
# 在 Role 定义中声明知识权限（Phase 2 Role DSL）
Role:
  name: data-analyst
  scope:
    domains: [sales, marketing]
  capabilities:
    - KNOWLEDGE_QUERY
  knowledge_restrictions:
    allowed_types: [BigQuery Table, Metric, Playbook, Reference]
    denied_types: [Security Playbook, HR Policy]
    max_depth: 3              # 图遍历最大深度限制
  perspective:
    fields_visible: [...]     # 继承自 Perspective Engine
```

### 8. SQL 层过滤实现

```sql
-- 搜索查询自动注入权限过滤
SELECT * FROM knowledge_articles
WHERE search_vector @@ to_tsquery('english', :query)
  AND deleted = false

  -- Layer 1: 已由 API 网关的 Capability 检查保证

  -- Layer 2: Domain 过滤
  AND domain = ANY(:allowedDomains)           -- ['sales', 'marketing']

  -- Layer 3: Type 过滤
  AND type != ALL(:deniedTypes)               -- ['Security Playbook', 'HR Policy']

  -- 状态过滤：draft/review 仅对管理员可见
  AND (status = 'published' 
       OR (status IN ('draft','review') AND :isAdmin = true))

ORDER BY ts_rank(search_vector, to_tsquery('english', :query)) DESC
LIMIT :limit OFFSET :offset;
```

### 9. 跨域知识共享（Phase 4）

某些知识条目需要跨 domain 可见（如公司级别的数据治理政策）。

```yaml
---
type: Policy
title: Company-wide Data Classification Standard
domain: "*"                     # 通配符：所有 domain 可见
status: published
---
```

通配符 `domain: "*"` 绕过 Layer 2 过滤。Phase 4 可扩展为显式的 domain 列表：`domain: [sales, marketing, engineering]`。

### 10. 审计集成

所有知识访问操作通过 `ChangeEventInterceptor` 审计：

```java
// 自动记录的事件类型
public enum KnowledgeEventType {
    KNOWLEDGE_SEARCH,        // Agent 搜索了知识库（记录 query + 结果数）
    KNOWLEDGE_CONTEXT_FETCH, // Agent 获取了某个知识条目的上下文
    KNOWLEDGE_TRAVERSE,      // Agent 执行了图遍历
    KNOWLEDGE_PREREQUISITE,  // Agent 获取了 Action 执行前的必备知识
    KNOWLEDGE_ACCESS_DENIED  // Agent 尝试访问无权查看的知识条目
}

// 审计日志条目示例
// {
//   "entity_type": "knowledgeArticle",
//   "entity_fqn": "knowledge.primary.policies.tier-update",
//   "event_type": "KNOWLEDGE_CONTEXT_FETCH",
//   "actor": "agent://data-analyst-agent/v3",
//   "context": {
//     "action_attempted": "resourceType.Customer.update_tier",
//     "knowledge_retrieved": 2,
//     "denied_by_perspective": 0
//   },
//   "timestamp": "2026-06-22T14:30:00Z"
// }
```

### 11. Agent 行为能力边界

```
                    KNOWLEDGE_QUERY      KNOWLEDGE_CREATE    KNOWLEDGE_ADMIN
                    ─────────────        ────────────────    ───────────────
搜索知识             ✅                   ✅                   ✅
读取知识正文          ✅                   ✅                   ✅
图遍历               ✅ (max_depth限制)   ✅                   ✅
获取Action必备知识    ✅                   ✅                   ✅
创建知识条目          ❌                   ✅ (via Git)        ✅
管理知识源            ❌                   ❌                   ✅
修改生命周期          ❌                   ❌                   ✅
查看未发布/已归档     ❌                   ✅ (自己的草稿)      ✅
```

大多数 Agent Role 只需要 `KNOWLEDGE_QUERY`——这正是设计目标：Agent 广泛消费知识但无法篡改。

---

## 分阶段计划

### Phase 3（Agent SDK + 语义搜索）

- [ ] SDK 方法：`search`、`getContext`、`traverse`、`getPrerequisites`、`list`
- [ ] `getPrerequisites` 匹配引擎
- [ ] 自动上下文注入模式（SDK 拦截 `execute`）
- [ ] RAG 模式集成（`agent.chat()` 自动搜索知识库）
- [ ] 审计事件：KNOWLEDGE_SEARCH、KNOWLEDGE_CONTEXT_FETCH、KNOWLEDGE_PREREQUISITE

### Phase 2（Perspective 集成）

- [ ] `KnowledgeCapability` 定义（KNOWLEDGE_QUERY、KNOWLEDGE_CREATE、...）
- [ ] Role DSL 中的 `knowledge_restrictions` 配置
- [ ] 搜索/SDK 查询时透明的权限过滤（SQL 层注入 WHERE 条件）
- [ ] 审计事件：KNOWLEDGE_ACCESS_DENIED

### Phase 4（跨域共享 + Agent 自主知识生成）

- [ ] 跨 domain 知识共享（`domain: "*"` 或多 domain 列表）
- [ ] Agent 自动知识生成：操作后总结 → draft KnowledgeArticle
- [ ] Agent 知识质量反馈：Agent 标记 knowledge entry 是否准确/有用

---

## 后果

### 积极

- **Agent 不只是搜索工具的用户**——知识库成为 Agent 决策流程的有机部分
- **上下文注入减少幻觉**：Agent 在执行 Action 前自动获得相关业务规则，比 prompt 中写「不要违反政策」更可靠
- **权限统一**：知识库不引入独立授权体系，使用与语义层相同的 Role→Capability 模型
- **可审计**：Agent 的知识消费行为完整记录在 Event Log 中

### 消极

- **SDK 需要额外的 LLM 调用**（summarize 功能）——增加延迟和成本
- **权限过滤增加查询复杂度**——需要在每个知识查询中注入 WHERE 条件
- **getPrerequisites 的匹配质量依赖引用完整性**——如果知识条目与实体的引用关系不完整，Agent 获取的上下文有缺口

---

## 备选方案

### 方案 A：Agent 直接调 REST API，不做 SDK

**放弃理由**：Agent 需要的不只是数据，而是**上下文编排**。getPrerequisites 的匹配逻辑、RAG 上下文格式化、摘要裁剪——这些在 SDK 层封装比每个 Agent 自行实现好得多。

### 方案 B：知识库不做权限过滤，依赖文件系统权限

**放弃理由**：Heirloom 的核心价值之一是统一授权。知识库不做权限，等于在安全模型上开了后门——Agent 可以通过知识条目读到它无权访问的数据资产的文档描述。

---

## 参考

- ADR-032: Knowledge Base Module — Architecture Design
- ADR-034: Knowledge Graph & Lifecycle Governance
- ADR-002: Ability 作为类型层契约
- ADR-005: 九步 Action 校验流水线
- ADR-009: Perspective Engine 的不可绕过位置
- ADR-017: 可插拔 Authorizer
