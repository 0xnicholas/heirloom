# ADR-034: Knowledge Graph & Lifecycle Governance

**关联文档**: [ADR-032 知识库架构](./032-knowledge-base-architecture.md)、[ADR-032b 深度设计](./032b-knowledge-base-deep-dive.md)、[ADR-033 转换管道](./033-knowledge-conversion-pipeline.md)
**状态**: Proposed
**日期**: 2026-06-22

---

## 上下文

KnowledgeArticle 之间存在 Markdown 链接，KnowledgeArticle 通过 `references` 引向 Heirloom 元数据实体（Table、Column、ResourceType 等）。这些引用构成一个异构图——知识之间的链接和知识到实体的链接交织在一起。当前设计仅支持单跳查询（`?ref={fqn}`），无法遍历多跳关系。

同时，知识内容会演化——作者编辑、自动生成、审核发布、废弃归档。需要一个生命周期模型来追踪每个知识条目的状态，以及一套质量指标来度量知识库的健康程度。

---

## Part 1: 知识图谱

### 1. 图模型

```
节点类型:
  知识节点: KnowledgeArticle（type, status, domain）
  实体节点: 任何 HeirloomEntity（Table, ResourceType, Lineage, GlossaryTerm, ...）

边类型:
  KNOWLEDGE_REF    知识条目 A → 知识条目 B       (Markdown 链接解析)
  ENTITY_REF       知识条目   → Heirloom 实体    (EntityReference)
  RESOURCE_OF      知识条目   → Heirloom 实体    (frontmatter.resource 字段)
  SAME_DOMAIN      知识条目 A ↔ 知识条目 B       (domain 相同，隐式边)
  SAME_TAG         知识条目 A ↔ 知识条目 B       (共享 tag，隐式边)
  DERIVED_FROM     知识条目   → 知识条目          (模板/自举生成)
```

**示例子图**：

```
knowledge.primary.tables.orders ──ENTITY_REF──▶ metadata_tables.sales.orders
         │                                              │
         │ KNOWLEDGE_REF                          LINEAGE_TO
         │                                              │
         ▼                                              ▼
knowledge.primary.playbooks.incident      metadata_tables.sales.shipments
         │                                              ▲
         │ ENTITY_REF                                   │
         └──────────────────────────────────────────────┘
              "orders" referenced in playbook
```

### 2. 图查询 API

#### 2.1 遍历端点

```
GET /v1/knowledge/graph/traverse
  ?from={fqn}                      # 起点 FQN（知识条目或 Heirloom 实体）
  &direction=outgoing|incoming|both
  &edgeTypes=ENTITY_REF,KNOWLEDGE_REF
  &maxDepth=3                      # 最大跳数
  &limit=50                        # 最多返回节点数
  &includeEntities=true            # 是否包含实体节点（默认 true）

Response:
{
  "nodes": [
    {
      "fqn": "knowledge.primary.tables.orders",
      "type": "knowledge",
      "knowledgeType": "BigQuery Table",
      "title": "Orders",
      "status": "published"
    },
    {
      "fqn": "metadata_tables.sales.orders",
      "type": "entity",
      "entityType": "table",
      "name": "orders"
    }
  ],
  "edges": [
    {
      "from": "knowledge.primary.tables.orders",
      "to": "metadata_tables.sales.orders",
      "type": "ENTITY_REF",
      "label": "主数据源"
    },
    {
      "from": "knowledge.primary.tables.orders",
      "to": "knowledge.primary.playbooks.incident",
      "type": "KNOWLEDGE_REF",
      "label": "相关操作手册"
    }
  ],
  "stats": {
    "knowledgeNodes": 3,
    "entityNodes": 5,
    "edges": 8,
    "maxDepthReached": 2
  }
}
```

#### 2.2 遍历算法（SQL）

```sql
-- 递归 CTE 实现图遍历
-- Phase 0-2 使用 PostgreSQL。Phase 4 可替换为专用图数据库。
WITH RECURSIVE traverse AS (
    -- 锚点：起始节点
    SELECT fqn, fqn as path, 0 as depth
    FROM knowledge_articles
    WHERE fully_qualified_name = :fromFqn

    UNION

    -- 扩展：从当前节点沿 references 边向外跳转
    SELECT ref->>'fqn',
           t.path || '→' || ref->>'fqn',
           t.depth + 1
    FROM traverse t,
         knowledge_articles ka,
         jsonb_array_elements(ka.references_jsonb) ref
    WHERE ka.fully_qualified_name = t.fqn
      AND t.depth < :maxDepth
      AND ref->>'fqn' IS NOT NULL
      AND ref->>'fqn' NOT LIKE '%' || t.path || '%'  -- 防止环路
)
SELECT DISTINCT fqn, depth FROM traverse;
```

#### 2.3 路径查询

```
GET /v1/knowledge/graph/path
  ?from={fqnA}
  &to={fqnB}
  &maxDepth=5

# 返回 from 到 to 的最短路径（BFS），包含所有中间节点和边
```

### 3. 图分析 API

#### 3.1 覆盖分析

```
GET /v1/knowledge/graph/coverage
  ?entityType=table                # 分析哪种实体的知识覆盖情况
  &domain=sales

Response:
{
  "summary": {
    "totalEntities": 45,           # 该 domain 下所有 table
    "coveredEntities": 32,         # 有至少一篇知识条目的 table
    "coverageRate": 0.71,
    "orphanEntities": 13           # 无知识覆盖的 table
  },
  "orphans": [
    {"fqn": "metadata_tables.sales.legacy_2019", "name": "legacy_2019", "description": null},
    ...
  ],
  "topCovered": [
    {"fqn": "metadata_tables.sales.orders", "articleCount": 5, "articles": [...]},
    ...
  ]
}
```

#### 3.2 影响分析

```
GET /v1/knowledge/graph/impact
  ?entityFqn=metadata_tables.sales.orders
  &direction=incoming              # 哪些知识条目引用了这张表

# 用于评估「如果 orders 表结构变更，需要更新哪些知识条目」
```

#### 3.3 聚类

```
GET /v1/knowledge/graph/clusters
  ?minSize=3                       # 最少节点数

# 使用连通分量算法找出知识的自然聚类
# 「所有围绕 customer 这个概念的表格、指标、playbook」
```

---

## Part 2: 知识生命周期

### 4. 状态机

```
                    ┌─────────┐
               ┌───▶│  draft  │◀──────────┐
               │    └────┬────┘           │
               │         │ submit         │ (reviewer rejects)
               │         ▼                │
               │    ┌─────────┐          │
               │    │ review  │──────────┘
               │    └────┬────┘
               │         │ approve
               │         ▼
               │    ┌──────────┐     deprecate    ┌───────────┐
               │    │ published │────────────────▶│  archived  │
               │    └──────────┘                  └─────┬─────┘
               │         │                              │
               │         │ deprecate (direct)            │ reactivate
               │         ▼                              ▼
               │    ┌───────────┐               ┌───────────┐
               └────│  archived  │◀──────────────│ published  │
                    └───────────┘               └───────────┘
```

| 状态 | 含义 | 搜索可见 | Agent 可见 |
|------|------|---------|-----------|
| `draft` | 作者正在编写 | ❌ | ❌ |
| `review` | 等待审批 | ❌ | ❌ |
| `published` | 已发布，正式可用 | ✅ | ✅ |
| `archived` | 已废弃，保留作为历史参考 | ⚠️ 明确搜索 `status=archived` 才可见 | ✅ 带 `deprecated` 标记 |

**为什么没有 `deleted` 状态？** 文件删除由 `KnowledgeSyncEngine` 检测（文件不存在 → `deleted = true`），不走状态机。归档是人类主动标记内容不再适用，文件仍保留。

### 5. 状态转换

```java
public enum KnowledgeStatus {
    DRAFT, REVIEW, PUBLISHED, ARCHIVED;

    public Set<KnowledgeStatus> validTransitions() {
        return switch (this) {
            case DRAFT     -> Set.of(REVIEW, PUBLISHED);    // 可直接发布（小改动）
            case REVIEW    -> Set.of(PUBLISHED, DRAFT);     // approve or reject
            case PUBLISHED -> Set.of(ARCHIVED, DRAFT);      // 废弃或回炉
            case ARCHIVED  -> Set.of(PUBLISHED);
        };
    }
}
```

### 6. 状态转换在文件层的体现

状态存储在文件的 frontmatter 中（`status` 字段），而非仅在数据库。这保证了文件目录的自足性：

```yaml
---
type: Playbook
title: Incident Response — Freshness Alert
status: review
---
```

同步引擎解析 frontmatter 时提取 `status` 字段。如果 frontmatter 中没有 `status`，默认值为 `published`（假设文件存在即已发布）。

**文件层与数据库层的状态一致性**：
- 文件是真相源——作者在文件中修改 `status` → git push → 同步到数据库
- Heirloom API **不提供**直接修改 KnowledgeArticle 状态的能力——状态通过文件管理

### 7. 审批集成

**Phase 0-2 不做审批集成**。review→published 状态转换通过文件修改完成（作者编辑 frontmatter → git push → sync），与 draft→published（小改动直达）一样。

**Phase 2 引入审批的前提**：Heirloom 具备通过 Git API 写文件的能力。审批流程为：

```java
/**
 * Phase 2 审批流程（前提：Heirloom 可通过 Git API 修改知识文件）
 * 
 * 1. 作者修改文件 status: review → git push → sync
 * 2. 系统检测到 review 状态 → 创建 Proposal(targetEntityType=knowledgeArticle, status=PENDING)
 * 3. 审批人批准 → Proposal 状态变更为 APPROVED
 * 4. 系统通过 Git API 修改文件中的 status: published → commit + push
 * 5. 下次 sync 后数据库状态更新为 published
 * 
 * 如果 Proposal 被拒：
 * 6. 审批人拒绝 → Proposal 状态变更为 REJECTED
 * 7. 系统在文件中追加 rejection comment（或创建 review comment Issue）
 * 8. 作者收到通知，自行修改文件
 * 
 * 注意：步骤 4 的 Git 写操作可能在 git push 时因冲突失败——
 * 需要重试机制和人工回退路径。
 */
public class KnowledgeApprovalIntegrator {
    // Phase 2 only — requires Git write capability
```

---

## Part 3: 质量治理

### 8. 质量维度

| 维度 | 指标 | 计算方式 | 权重 |
|------|------|---------|------|
| **完整性** | 引用覆盖率 | `resolvedReferences / totalLinks` | 0.25 |
| **时效性** | 最后更新时间 | `now - updatedAt` → 分段评分 | 0.20 |
| **丰富度** | 正文长度 | `len(body)` → 分段评分（> 500 字符满分） | 0.15 |
| **结构完整性** | frontmatter 字段填充率 | `filledFields / recommendedFields` | 0.15 |
| **连接度** | 被引用次数 | `incomingRefCount` → 分段评分 | 0.15 |
| **一致性** | 文件 hash 与 DB 记录一致 | `fileHash == dbHash` → 0 或 1 | 0.10 |

### 9. 评分算法

```java
public class KnowledgeQualityScorer {

    public QualityScore score(KnowledgeArticle article, GraphStats graphStats) {
        double completeness = article.getResolvedRefCount() > 0
            ? (double) article.getResolvedRefCount() / Math.max(article.getTotalLinkCount(), 1)
            : 0.8;  // 无链接不算差

        double freshness = freshnessScore(article.getLastSyncedAt());
        double richness = clamp(len(article.getBody()) / 500.0, 0, 1);
        double structure = structureScore(article.getFrontmatter());
        double connectedness = clamp(graphStats.incomingRefCount() / 3.0, 0, 1);
        double consistency = article.getFileHash().equals(article.getChangeHash()) ? 1.0 : 0.5;

        double total = completeness * 0.25 + freshness * 0.20 + richness * 0.15
                     + structure * 0.15 + connectedness * 0.15 + consistency * 0.10;

        return new QualityScore(total, Map.of(
            "completeness", completeness,
            "freshness", freshness,
            "richness", richness,
            "structure", structure,
            "connectedness", connectedness,
            "consistency", consistency
        ));
    }

    private double freshnessScore(Instant lastSynced) {
        long daysSince = Duration.between(lastSynced, Instant.now()).toDays();
        if (daysSince <= 30) return 1.0;
        if (daysSince <= 90) return 0.7;
        if (daysSince <= 180) return 0.4;
        return 0.1;
    }
}

public record QualityScore(double overall, Map<String, Double> dimensions) {
    public String tier() {
        if (overall >= 0.8) return "excellent";
        if (overall >= 0.6) return "good";
        if (overall >= 0.4) return "fair";
        return "poor";
    }
}
```

### 10. 质量报告 API

```
GET /v1/knowledge/quality/report?domain=sales

Response:
{
  "overallScore": 0.72,
  "tier": "good",
  "dimensionScores": {
    "completeness": 0.85,
    "freshness": 0.68,
    "richness": 0.55,
    "structure": 0.90,
    "connectedness": 0.42,
    "consistency": 0.95
  },
  "articlesByTier": {
    "excellent": 12,
    "good": 28,
    "fair": 15,
    "poor": 5
  },
  "topIssues": [
    {
      "articleFqn": "knowledge.primary.tables.archived_sales",
      "issue": "stale",
      "severity": "warning",
      "detail": "Last updated 210 days ago"
    },
    {
      "articleFqn": "knowledge.primary.metrics.revenue",
      "issue": "orphan",
      "severity": "info",
      "detail": "No incoming references from other knowledge articles"
    }
  ]
}
```

### 11. 自动治理操作

```java
/**
 * 定期（每周）执行的质量巡检与自动操作。
 * 自动操作限于非破坏性变更。
 */
@Component
public class KnowledgeGovernanceScheduler {

    /**
     * 自动归档：published 状态 + 最后更新 > 365 天 + 无 incoming 引用
     * → 生成 Proposal 建议归档
     */
    @Scheduled(cron = "0 0 2 * * SUN")  // 每周日凌晨 2 点
    public void suggestArchival() {
        List<KnowledgeArticle> stale = knowledgeArticleRepo
            .findStalePublished(365);
        for (var article : stale) {
            if (graphService.incomingRefCount(article.getFqn()) == 0) {
                Proposal p = Proposal.suggestArchive(article);
                proposalRepo.create(p);
            }
        }
    }

    /**
     * 质量评分更新（异步）
     */
    @Async
    @EventListener
    public void onSyncCompleted(SyncCompletedEvent event) {
        for (var article : event.getUpdatedArticles()) {
            QualityScore score = scorer.score(article, graphService.getStats(article.getFqn()));
            articleRepo.updateQualityScore(article.getId(), score);
        }
        
        // 异步刷新覆盖物化视图
        refreshCoverageView();
    }

    /**
     * 刷新知识覆盖物化视图。
     * 使用 CONCURRENTLY 避免阻塞读取查询。
     */
    @Async
    public void refreshCoverageView() {
        jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY knowledge_coverage");
        log.debug("Coverage materialized view refreshed");
    }
}
```

---

## Part 4: 数据模型扩展

### 12. KnowledgeArticle 新增字段

```java
// 新增到 KnowledgeArticle（需数据库迁移 V5）

// === 生命周期字段 ===
@Column(length = 32, nullable = false)
private String status = "published";            // draft | review | published | archived

@Column(length = 256)
private String reviewedBy;

private Instant reviewedAt;

// === 图分析缓存字段（异步计算，避免实时扫描大图） ===
private Integer incomingRefCount = 0;            // 被引用次数（所有类型引用之和）
private Integer outgoingRefCount = 0;            // 引用次数
private Integer entityRefCount = 0;              // 引向 Heirloom 实体的数量
private Integer resolvedRefCount = 0;            // outgoing 中能解析到有效实体的数量
private Integer totalLinkCount = 0;              // body 中所有 Markdown 链接总数（含未解析）

// === 质量评分 ===
@Type(JsonType.class)
@Column(columnDefinition = "jsonb")
private QualityScore qualityScore;               // 总体评分 + 各维度分解
```

### 13. 新增数据库索引

```sql
-- V5__knowledge_graph_lifecycle.sql

-- 生命周期相关索引
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'published';
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(256);
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_ka_status ON knowledge_articles(status, domain);

-- 图分析缓存字段
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS incoming_ref_count INTEGER DEFAULT 0;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS outgoing_ref_count INTEGER DEFAULT 0;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS entity_ref_count INTEGER DEFAULT 0;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS resolved_ref_count INTEGER DEFAULT 0;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS total_link_count INTEGER DEFAULT 0;
ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS quality_score JSONB;

-- 按连接度排序（热门知识条目）
CREATE INDEX IF NOT EXISTS idx_ka_incoming_refs ON knowledge_articles(incoming_ref_count DESC);

-- 覆盖分析：找出无知识覆盖的实体
-- 需要 LEFT JOIN knowledge_articles.references_jsonb 到 metadata_tables/其他实体表
-- 此类查询不适合单索引，使用物化视图（Phase 2+）
```

### 14. 物化视图——覆盖分析（Phase 2）

```sql
-- 实时覆盖分析可能较慢，使用物化视图定时刷新
CREATE MATERIALIZED VIEW IF NOT EXISTS knowledge_coverage AS
SELECT 
    'table' AS entity_type,
    mt.fully_qualified_name AS entity_fqn,
    mt.name AS entity_name,
    COUNT(ka.id) AS article_count,
    CASE WHEN COUNT(ka.id) > 0 THEN true ELSE false END AS is_covered
FROM metadata_tables mt
LEFT JOIN knowledge_articles ka 
    ON ka.references_jsonb @> jsonb_build_array(jsonb_build_object('fqn', mt.fully_qualified_name))
    AND ka.deleted = false
    AND ka.status = 'published'
GROUP BY mt.fully_qualified_name, mt.name;

CREATE UNIQUE INDEX IF NOT EXISTS idx_coverage_fqn ON knowledge_coverage(entity_fqn);

-- 刷新策略：每次 sync 完成后异步刷新（避免阻塞同步）
-- 对应 Java: KnowledgeGovernanceScheduler.refreshCoverageView()
-- 使用 CONCURRENTLY 避免锁表
-- REFRESH MATERIALIZED VIEW CONCURRENTLY knowledge_coverage;
```

---

## 整合视图

### 知识库全模块架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         知识库模块                                  │
│                                                                     │
│  ┌─────────────────────┐  ┌──────────────────────────────────────┐ │
│  │   Knowledge Graph    │  │   Lifecycle & Governance             │ │
│  │                     │  │                                      │ │
│  │  遍历 / 路径 / 聚类  │  │  draft → review → published → archive │ │
│  │  覆盖分析 / 影响分析  │  │  质量评分 / 自动归档建议              │ │
│  │  物化视图            │  │  Proposal 审批集成                    │ │
│  └──────────┬──────────┘  └──────────────────┬───────────────────┘ │
│             │                                │                      │
│  ┌──────────▼────────────────────────────────▼───────────────────┐ │
│  │                     KnowledgeArticle                          │ │
│  │  status / qualityScore / incomingRefCount / entityRefCount   │ │
│  └──────────────────────────────┬───────────────────────────────┘ │
│             │                                                      │
│  ┌──────────▼──────────────────────────────────────────────────┐   │
│  │  Sync Engine  │  Conversion Pipeline  │  Search Engine       │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 分阶段计划

### Phase 0.5：基础生命周期

- [ ] `status` 字段 + 状态机校验
- [ ] frontmatter 中 `status` 字段的解析与同步
- [ ] 图分析缓存字段（incomingRefCount 等）——同步时计算
- [ ] 数据库迁移 V5

### Phase 1：图遍历 + 基础治理

- [ ] 图遍历 API（`/v1/knowledge/graph/traverse`）
- [ ] 覆盖分析（`/v1/knowledge/graph/coverage`）
- [ ] 影响分析（`/v1/knowledge/graph/impact`）
- [ ] 质量评分引擎 + API（`/v1/knowledge/quality/report`）

### Phase 2：审批集成

- [ ] review → published 流程通过 Proposal 系统
- [ ] 自动归档建议（`suggestArchival`）
- [ ] 知识覆盖物化视图

### Phase 4：高级图分析

- [ ] 图聚类（连通分量）
- [ ] 路径查询（BFS 最短路径）
- [ ] 专用图数据库后端（Neo4j，可选）

---

## 后果

### 积极

- **Agent 可遍历知识图谱**：从一张表出发，沿着引用边发现相关的操作手册、指标定义、业务规则
- **知识库健康可度量**：质量评分使团队可以量化知识库的完整性和时效性
- **治理自动化**：自动归档建议减少过期内容堆积
- **冷启动有度量**：覆盖分析直观显示哪些数据资产缺少知识文档

### 消极

- **图分析缓存字段需要同步时计算**：增加同步耗时
- **物化视图需要定时刷新**：覆盖分析不是实时的
- **质量评分主观权重**：0.25/0.20/... 的权重分配需要实际使用中调整

---

## 备选方案

### 方案 A：不作图分析，只用简单引用查询

**放弃理由**：单跳 `?ref=` 查询无法满足 Agent 探索知识的需要。Agent 需要从「orders 表」出发发现「orders 相关的一切」——这需要在引用图中多跳遍历。

### 方案 B：用 Neo4j 从一开始就做图存储

**放弃理由**：Phase 0-3 的数据量（几百到几千篇文档，几千条引用边）用 PostgreSQL 递归 CTE 完全够用。引入 Neo4j 增加运维复杂度，Phase 4 规模扩大后才是合适的切换时机。

---

## 参考

- ADR-032: Knowledge Base Module — Architecture Design
- ADR-032b: Deep Dive
- ADR-033: Conversion Pipeline
- ADR-002: Ability 作为类型层契约（Proposal 系统）
- ADR-026: ResourceType Lifecycle — Six Stages
