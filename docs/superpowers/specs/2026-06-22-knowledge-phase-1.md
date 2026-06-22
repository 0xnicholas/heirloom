# Spec: Knowledge Base — Phase 1 (Search, References, Graph, Quality)

**日期**: 2026-06-22
**依赖**: Phase 0.5c
**范围**: FTS 搜索, 链接→引用解析, 反向引用, 图遍历, 质量评分

---

## 1. Implementation Order

| # | 能力 | 依赖 |
|---|------|------|
| 1 | **FTS 搜索** `GET /v1/knowledge/search?q=...` | PostgreSQL tsvector index |
| 2 | **链接→EntityReference** 同步时解析 Markdown `[text](url)` | FrontmatterParser 扩展 |
| 3 | **反向引用** `?ref={fqn}` | #2 的 references JSONB |
| 4 | **图遍历 API** `GET /v1/knowledge/graph/traverse` | #2 + #3 |
| 5 | **质量评分** `GET /v1/knowledge/quality/report` | #2 的引用完整性数据 |

---

## 2. FTS Search

### 2.1 实现

```sql
-- Add generated tsvector column
ALTER TABLE knowledge_articles ADD COLUMN search_vector tsvector 
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(title,'')), 'A') ||
    setweight(to_tsvector('english', coalesce(description,'')), 'B') ||
    setweight(to_tsvector('english', coalesce(body,'')), 'C')
  ) STORED;

CREATE INDEX idx_ka_search ON knowledge_articles USING GIN (search_vector);
```

### 2.2 API

```
GET /v1/knowledge/search?q=orders+table&type=BigQuery+Table&tags=sales&domain=default&limit=20&offset=0
```

### 2.3 查询清洗

输入 `q` → 移除特殊字符 → 分词 → 添加 `:*` 前缀匹配 → `to_tsquery`

---

## 3. Link → EntityReference

### 3.1 在 FrontmatterParser 之后新增 LinkResolver

扫描 body 中的 `[text](url)` 模式：
- `http(s)://` → ExternalCitation
- `.md` 后缀 → 映射为 KnowledgeArticle FQN → EntityReference
- 其他 → 记录为 UnresolvedReference

### 3.2 解析时机

同步引擎 `buildArticle` 时调用 `LinkResolver.resolve(body)`，结果合并到 `references`。

---

## 4. Graph Traversal API

### 4.1 API

```
GET /v1/knowledge/graph/traverse?from={fqn}&maxDepth=2

GET /v1/knowledge/graph/coverage?entityType=table&domain=sales

GET /v1/knowledge/graph/impact?entityFqn={fqn}
```

### 4.2 实现

递归 CTE 遍历 `references_jsonb` 边。覆盖分析用 LEFT JOIN 到 metadata_tables。

---

## 5. Quality Scoring

### 5.1 评分维度（6 项，ADR-034 §8）

| 维度 | 权重 | 计算 |
|------|------|------|
| 完整性 | 0.25 | resolvedRefCount / totalLinkCount |
| 时效性 | 0.20 | days since lastSyncedAt → 分段 |
| 丰富度 | 0.15 | len(body) / 500 |
| 结构 | 0.15 | frontmatter 字段填充率 |
| 连接度 | 0.15 | incomingRefCount / 3 |
| 一致性 | 0.10 | fileHash == dbHash |

### 5.2 API

```
GET /v1/knowledge/quality/report?domain=sales
→ overallScore, dimensionScores, articlesByTier, topIssues
```

---

## 6. Tests

| 组件 | 测试 | 关键场景 |
|------|------|---------|
| FTS Search | 4 | 关键词匹配, type 过滤, tag 过滤, 空结果 |
| LinkResolver | 5 | http link, .md link, relative path, mixed, edge cases |
| Graph Traversal | 3 | 单跳, 两跳, 最大深度限制 |
| Quality Scorer | 3 | 满分文档, 缺失文档, mixed scores |
