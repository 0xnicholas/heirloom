# Spec: Knowledge Base — Phase 1 Semantic Search (pgvector + Hybrid)

**日期**: 2026-06-22
**依赖**: Phase 0.5a (KnowledgeArticle), Phase 1 FTS search
**范围**: embedding 生成, 向量搜索, 混合搜索 (RRF), 搜索 mode 参数

---

## 1. Scope

### In scope

| 能力 | 说明 |
|------|------|
| Embedding 生成 | 同步时为文章生成 embedding（结构化模板） |
| 批量生成 | 每次 sync 结束后批量生成全部 pending embedding |
| 向量搜索 | `mode=vector` → pgvector `<=>` cosine distance 排序 |
| 混合搜索 | `mode=hybrid` → RRF 融合 FTS rank + vector similarity |
| Embedding provider 可插拔 | 接口抽象，默认 `OpenAiEmbeddingProvider` |
| 生成状态追踪 | `embedStatus` 字段: PENDING / OK / FAILED / DISABLED |
| 回退安全 | provider 不可用时自动回退 fts |

### Out of scope

| 能力 | 归属 |
|------|------|
| LLM 摘要 (summarize) | Phase 3 Agent SDK |
| 多语言 embedding | Phase 3+ |

---

## 2. Architecture

```
KnowledgeSyncEngine.sync()
  │
  ├─ syncUpsert(article)           # 保存，embedStatus = PENDING
  │
  └─ (after all files processed)
       │
       EmbeddingBatchService.generateAll(sourceFqn)
       │
       ├─ SELECT * WHERE embedStatus = 'PENDING'
       ├─ 分批（每批 20 条）
       ├─ EmbeddingProvider.embedBatch(texts[]) → float[][]
       └─ UPDATE embedding, embedStatus = 'OK'
```

### 2.1 内容模板

结构化输入格式（优化 LLM embedding 质量）：

```
"Title: {title}. Type: {type}. Description: {description}. {body_truncated_1000}"
```

- `title` — 知识条目标题
- `type` — OKF 类型（如 "BigQuery Table", "Playbook"）
- `description` — 摘要
- `body_truncated_1000` — 正文截断到 1000 字符

### 2.2 EmbeddingProvider 接口

```java
public interface EmbeddingProvider {
    /** 单条 embed */
    float[] embed(String text);
    /** 批量 embed（默认循环调用 embed，子类可覆盖优化） */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
    int dimension();
    boolean isAvailable();
}
```

**默认实现**: `OpenAiEmbeddingProvider`
- 模型: `text-embedding-3-small`（1536 维）
- 批量: 覆盖 `embedBatch`，单次 API 调用发送多条
- 配置: `heirloom.knowledge.embedding.openai.api-key=${OPENAI_API_KEY}`

### 2.3 状态字段

```sql
ALTER TABLE knowledge_articles ADD COLUMN embed_status VARCHAR(16) DEFAULT 'PENDING';
-- PENDING  = 等待生成
-- OK       = 已生成
-- FAILED   = 生成失败（保留原因为下次重试）
-- DISABLED = embedding 功能未启用
```

### 2.4 pgvector 扩展

```sql
CREATE EXTENSION IF NOT EXISTS vector;

-- ivfflat 索引（1000 条以上才有效）
CREATE INDEX IF NOT EXISTS idx_ka_embedding
  ON knowledge_articles
  USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 100);
```

---

## 3. Search Modes

| mode | 查询 | 排序 | 需要 |
|------|------|------|------|
| `fts` (默认) | `ts_query` | `ts_rank` DESC | 无 |
| `vector` | `<=>` cosine distance | distance ASC | embedding API 调用一次 |
| `hybrid` | 两者都执行 | RRF 融合 | embedding API 调用一次 |

### 3.1 RRF 融合算法

RRF Score = SUM_over_sources( 1.0 / (k + rank_in_source) )，k = 60

```
fts (by ts_rank):
  1. id1 (0.9)        rank 1
  2. id2 (0.7)        rank 2
  3. id3 (0.5)        rank 3

vector (by cosine similarity):
  1. id2 (0.95)       rank 1
  2. id3 (0.80)       rank 2
  3. id4 (0.60)       rank 3

计算:
  RRF(id1) = 1/(60+1)                               = 0.0164
  RRF(id2) = 1/(60+2) + 1/(60+1)                    = 0.0325
  RRF(id3) = 1/(60+3) + 1/(60+2)                    = 0.0317
  RRF(id4) = 1/(60+3)                               = 0.0159

融合排序: id2 (0.0325) > id3 (0.0317) > id1 (0.0164) > id4 (0.0159)
```

### 3.2 mode 回退策略

```
if mode == 'vector' || mode == 'hybrid':
  if !embeddingProvider.isAvailable():
    log.warn("Embedding unavailable, falling back to fts")
    mode = 'fts'
```

---

## 4. Database Migration (V5)

```sql
-- V5__knowledge_embedding.sql

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE knowledge_articles ADD COLUMN IF NOT EXISTS embed_status VARCHAR(16) DEFAULT 'PENDING';

CREATE INDEX IF NOT EXISTS idx_ka_embedding
  ON knowledge_articles
  USING ivfflat (embedding vector_cosine_ops)
  WITH (lists = 100);
```

---

## 5. Test Scenarios

| # | 场景 | 类型 |
|---|------|------|
| 1 | FTS 模式正常（向后兼容，已有） | 集成 |
| 2 | EmbeddingProvider.embed() 返回 1536 维向量 | 单元 |
| 3 | embedBatch 批量返回正确数量 | 单元 |
| 4 | 内容模板格式正确 | 单元 |
| 5 | vector 搜索对有 embedding 的文章按相似度排序 | 集成 |
| 6 | hybrid 模式 RRF 融合 FTS + vector | 集成 |
| 7 | embedding provider 不可用时 mode=vector 回退 fts | 单元 |
| 8 | embedding provider 不可用时 mode=hybrid 回退 fts | 单元 |
| 9 | RRF 计算函数正确（边界：单结果集、空结果、重叠结果） | 单元 |

---

## 6. Acceptance Criteria

1. `mode=fts` 行为不变（向后兼容）
2. `mode=vector` 对含 embedding 的文章按 cosine similarity 排序
3. `mode=hybrid` 用 RRF 融合 FTS rank 和 vector similarity
4. embedding provider 不可用时 vector/hybrid 自动回退 fts
5. 同步后 embedStatus=PENDING 的文章被批量更新为 OK（或 FAILED）
6. 同步不因 embedding 生成失败而阻塞
