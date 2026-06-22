# ADR-032: Knowledge Base Module — Architecture Design

## 状态
Proposed

## 日期
2026-06-22

## 上下文

Heirloom 当前有两层架构：
- **元数据目录层**（Metadata Layer）：自动发现、采集和管理数据资产的元数据（Table、Column、Lineage 等）
- **语义操作层**（Semantic Layer）：为 AI Agent 提供类型安全的操作界面（ResourceType、Abilities、Action 等）

缺失第三层——**业务知识层**（Knowledge Layer）。当前系统能回答「数据在哪里、结构是什么、谁可以操作什么」，但无法回答「数据意味着什么、业务规则是什么、组织知道什么」。

OKF（Open Knowledge Framework）提供了一个参考格式：目录树 + Markdown + YAML frontmatter。其设计哲学（唯一必填字段 `type`，格式即契约，生产/消费解耦）与 Heirloom 的平台化方向互补。

本 ADR 定义知识库模块的完整架构，覆盖实体模型、存储设计、OKF 兼容层、API 设计和分阶段实施计划。

---

## 决策

### 1. 在 Heirloom 三层架构中引入知识层

```
┌──────────────────────────────────────────────────────────┐
│ 3. 业务知识层 (Knowledge Layer)         ← NEW            │
│    知识条目、业务规则、决策记录、FAQ、Playbook              │
│    存储：Markdown body + JSONB metadata + pgvector        │
│    兼容：OKF import/export                                │
│    搜索：全文检索 + 语义搜索 (Phase 3)                     │
├──────────────────────────────────────────────────────────┤
│ 2. 语义操作层 (Semantic Layer)                            │
│    ResourceType、Abilities、Action、Role→Capability、     │
│    Function、StateMachine                                 │
├──────────────────────────────────────────────────────────┤
│ 1. 元数据目录层 (Metadata Layer)                          │
│    Table、Column、Lineage、GlossaryTerm、Tag、Domain      │
│    Discovery Engine → EntityRepository → REST API        │
└──────────────────────────────────────────────────────────┘
```

**三层引用关系**（知识层可引用下两层，下两层不依赖知识层）：

```
知识层的 KnowledgeArticle.references[]  →  FQN 引向：
  ├── 元数据层：metadata_tables, metadata_lineage, glossary_terms, ...
  └── 语义层：resource_types, mapping_rules, actions (Phase 2), ...
```

### 2. 单一核心实体：KnowledgeArticle

决定用 **一个实体类型覆盖全部知识内容**，通过 `type` 字段区分语义。理由：

| 考量 | 多实体方案 | 单一实体方案 |
|------|-----------|------------|
| 与 OKF 对齐 | 需要映射层 | 天然对齐——OKF 也只用一个 `type` 区分 |
| 新知识类型扩展 | 需要新 Entity + Repository + Resource | 只需约定新的 `type` 值 + body 模板 |
| 统一搜索 | 跨实体查询 | 单表查询 |
| 治理复杂度 | 每种类型独立生命周期 | 统一生命周期 |

OKF 的设计哲学在此适用：**不在规范层枚举知识类型，让生产者自由定义**。

#### 2.1 实体定义

```java
@Entity
@Table(name = "knowledge_articles")
public class KnowledgeArticle implements HeirloomEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // === OKF 兼容字段 ===

    /** OKF type —— 自由字符串，如 "BigQuery Table", "Playbook", "BusinessRule" */
    @Column(nullable = false, length = 128)
    private String type;

    @NotBlank
    @Column(nullable = false, length = 256)
    private String name;

    /** 全文检索目标：title + description + body */
    @Column(length = 512)
    private String title;

    @Column(length = 1024)
    private String description;

    /** 完整的 Markdown 正文（含 frontmatter 序列化后的 block） */
    @Column(columnDefinition = "text", nullable = false)
    private String body;

    /**
     * 从 body 中解析的 frontmatter 元数据，JSONB 存储。
     * 包含 title、description、tags、resource 等已知字段，
     * 以及生产者自定义的扩展字段。
     * Consumer 端可按 key 查询过滤。
     */
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> frontmatter = new HashMap<>();

    /** 标签列表 */
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> tags = new ArrayList<>();

    /** 知识状态：draft → published → archived */
    @Column(length = 32, nullable = false)
    private String status = "draft";

    // === Heirloom 增强字段 ===

    /**
     * 对 Heirloom 内其他实体的结构化引用。
     * 每条引用：{ fqn, entityType, label? }
     * 例如：[{ fqn:"metadata_tables.sales.orders", entityType:"table", label:"主数据源" }]
     */
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<EntityReference> references = new ArrayList<>();

    /** 外部引用（URL）——区别于对 Heirloom 内部的引用 */
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<ExternalCitation> citations = new ArrayList<>();

    /** pgvector 向量索引——Phase 3 启用。Phase 0-2 NULL。 */
    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    // === 治理字段 ===

    /** 内容作者——区别于 owner（管理责任人） */
    @Column(length = 256)
    private String author;

    /** OKF 版本声明（用于导出兼容性检查） */
    @Column(length = 16)
    private String okfVersion = "0.1";

    // === 标准 HeirloomEntity 字段 ===
    private String fullyQualifiedName;
    private String domain = "default";
    private String owner;
    private Long version = 1L;
    private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt;
    private Instant updatedAt;
}
```

#### 2.2 引用组件类型

```java
// 内部引用——指向 Heirloom Entity
public record EntityReference(
    String fqn,          // 被引用实体的 FQN，如 "metadata_tables.sales.orders"
    String entityType,   // 被引用实体的类型，如 "table"
    String label         // 此引用在本知识条目中的角色，如 "主数据源", "上游依赖", "相关概念"
) {}

// 外部引用——指向 Heirloom 外部的资源
public record ExternalCitation(
    String url,          // 外部 URL
    String title,        // 引用标题
    String description   // 简短说明
) {}
```

#### 2.3 FQN 模式

```
knowledge.{domain}.{name}

例如：
  knowledge.marketing.customer-segmentation-guide
  knowledge.finance.revenue-recognition-policy
  knowledge.engineering.postgres-incident-runbook
```

FQN 构建规则：`EntityRepository.setFullyQualifiedName()` 实现，从 `domain` 和 `name` 字段拼接。

### 3. 数据库迁移

```sql
-- V3__knowledge_base.sql

CREATE TABLE IF NOT EXISTS knowledge_articles (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  type VARCHAR(128) NOT NULL,
  title VARCHAR(512),
  description VARCHAR(1024),
  body TEXT NOT NULL,
  frontmatter JSONB NOT NULL DEFAULT '{}',
  tags JSONB NOT NULL DEFAULT '[]',
  status VARCHAR(32) NOT NULL DEFAULT 'draft',
  domain VARCHAR(128) DEFAULT 'default',
  author VARCHAR(256),
  owner VARCHAR(256),
  okf_version VARCHAR(16) DEFAULT '0.1',
  references_jsonb JSONB NOT NULL DEFAULT '[]',
  citations_jsonb JSONB NOT NULL DEFAULT '[]',
  embedding VECTOR(1536),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 按 FQN 精确查询（EntityResource 标准模式）
-- UNIQUE 约束已由 fully_qualified_name 提供

-- 全文检索
CREATE INDEX IF NOT EXISTS idx_ka_fts
  ON knowledge_articles
  USING GIN (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(description,'') || ' ' || coalesce(body,'')));

-- 按类型聚合（OKF index.md 生成使用）
CREATE INDEX IF NOT EXISTS idx_ka_type ON knowledge_articles(type, domain);

-- 按状态过滤（draft / published / archived）
CREATE INDEX IF NOT EXISTS idx_ka_status ON knowledge_articles(status);

-- 按引用反向查询：找出所有引用实体 X 的知识条目
CREATE INDEX IF NOT EXISTS idx_ka_refs
  ON knowledge_articles
  USING GIN ((references_jsonb -> 'fqn'));

-- pgvector 索引（Phase 3）
-- CREATE INDEX IF NOT EXISTS idx_ka_embedding
--   ON knowledge_articles
--   USING ivfflat (embedding vector_cosine_ops)
--   WITH (lists = 100);
```

### 4. API 设计

#### 4.1 标准 CRUD（走 EntityResource 模式）

```
GET    /v1/knowledge                    # 列表（分页、过滤、排序）
GET    /v1/knowledge/{id}               # 按 ID 获取
GET    /v1/knowledge/name/{fqn}         # 按 FQN 获取
POST   /v1/knowledge                    # 创建
PUT    /v1/knowledge/{id}               # 更新
DELETE /v1/knowledge/{id}               # 软删除
```

标准字段过滤：`?fields=title,description,type,tags,status`

#### 4.2 搜索端点

```
GET /v1/knowledge/search?q={query}
   &type={type}                         # 按 OKF type 过滤
   &tags={tag1},{tag2}                  # 按标签过滤
   &status={draft|published|archived}
   &domain={domain}
   &ref={fqn}                           # 反向引用：找出所有引用此 FQN 的知识条目
   &limit=20&offset=0
```

搜索策略（分层）：
- **Phase 0-2**：PostgreSQL 全文检索（`ts_query` on title + description + body）
- **Phase 3**：语义搜索（pgvector `<=>` cosine distance on embedding）+ 混合排序

#### 4.3 引用端点

```
GET /v1/knowledge/{id}/references       # 获取此知识条目引用的所有实体
GET /v1/knowledge/{id}/referenced-by    # 反向引用：哪些知识条目引用此实体
```

#### 4.4 OKF 兼容端点

```
POST /v1/knowledge/import/okf           # 从 OKF bundle 导入
  Body: multipart/form-data
    - bundle: tar.gz 文件
    - domain: 目标 domain（可选，默认从目录名推导）
  
  返回：ImportReport { created, skipped, errors[] }

GET  /v1/knowledge/export/okf           # 导出为 OKF bundle
  Query params:
    - domain: 限制导出某个 domain（可选，默认全部）
    - type: 限制导出某个 type（可选）
    - status: published（默认只导出已发布内容）
  
  返回：application/gzip 文件流
```

##### 4.4.1 OKF Import 流程

```
1. 解压 tar.gz → 临时目录
2. 遍历 .md 文件（跳过 index.md、log.md）
3. 对每个文件：
   a. 解析 frontmatter（YAML → Map）
   b. 提取 type（必填），title、description、tags、resource、timestamp（可选）
   c. 提取 body（frontmatter 之后的所有内容）
   d. 构建 KnowledgeArticle：
      - name = 文件路径去 .md（如 "tables/orders" → "tables.orders"）
      - type = frontmatter.type
      - title = frontmatter.title || 文件名
      - description = frontmatter.description
      - body = 原始 Markdown（含 frontmatter）
      - frontmatter = 完整的 YAML 解析结果
      - tags = frontmatter.tags
      - okfVersion = "0.1"
      - status = "published"（导入内容默认为已发布）
   e. 解析 body 中的 Markdown 链接 → references[]（如果目标 FQN 存在于 EntityRegistry 中）
   f. persist → 写入数据库
4. 返回导入报告
```

##### 4.4.2 OKF Export 流程

```
1. 查询 knowledge_articles WHERE status = 'published' [AND domain = ?] [AND type = ?]
2. 按 type 分组，构建目录结构：
   bundle/
   ├── index.md
   ├── {type_1}/
   │   ├── index.md
   │   ├── {article.name}.md
   │   └── ...
   └── {type_2}/
       └── ...
3. 对每个 article，生成 .md 文件：
   - 文件内容 = article.body（原始 Markdown，含 frontmatter）
   - 如果 article.body 不含 frontmatter，自动生成：
     ---
     type: {article.type}
     title: {article.title}
     description: {article.description}
     resource: urn:heirloom:knowledge:{article.fqn}
     tags: [{article.tags}]
     timestamp: {article.updatedAt}
     ---
     {article.body}
4. 生成各层的 index.md
5. 打包为 tar.gz → 返回流
```

### 5. 与现有系统的集成点

#### 5.1 ChangeEventInterceptor（自动审计）

`KnowledgeArticle` 实现 `HeirloomEntity`，自动获得：
- CREATE / UPDATE / DELETE 事件写入 event_log
- 变更内容哈希（changeHash）

#### 5.2 Authorizer（Phase 2）

知识库授权模型与语义层对齐：
- `query` Ability → 读取知识条目
- `mutate` Ability → 创建/编辑知识条目
- `admin` Ability → 删除/归档知识条目

知识条目的权限粒度：
- **类型级**：Role 对特定 `type` 的知识条目的 CRUD 能力
- **域级**：Role 对特定 `domain` 的知识条目的可见性

#### 5.3 EntityRegistry

```java
// 新增常量
public static final String KNOWLEDGE_ARTICLE = "knowledgeArticle";

// 注册
register(KNOWLEDGE_ARTICLE, KnowledgeArticle.class,
    knowledgeArticleRepository, knowledgeArticleService,
    "knowledge.{domain}.{name}",
    "/v1/knowledge");
```

#### 5.4 Discovery Engine（可选集成）

Discovery Engine 可以自动生成知识条目：
- 从 Table 的 column comments 提取初始描述
- 从 dbt 模型的 `description` 字段生成知识条目草稿
- 标记 `status = draft`，`author = "discovery-engine"`

### 6. 分阶段实施计划

#### Phase 0.5（知识库基础——与 Phase 0 核心基础设施并行或紧随其后）

**目标**：知识条目的基本 CRUD + OKF import/export。不做搜索。

- [ ] `KnowledgeArticle` JPA Entity + `HeirloomEntity` 实现
- [ ] `KnowledgeArticleRepository` extends `EntityRepository<KnowledgeArticle>`
- [ ] `KnowledgeArticleResource` extends `EntityResource<KnowledgeArticle>`
- [ ] 数据库迁移 V3
- [ ] OKF import（单文件 → 单个 KnowledgeArticle）
- [ ] OKF export（单个 KnowledgeArticle → 单文件）
- [ ] 单元测试 + 集成测试

**实体常量**：`EntityRegistry.KNOWLEDGE_ARTICLE`

#### Phase 1（知识搜索与引用——与语义查询层并行）

**目标**：全文搜索 + 结构化引用。

- [ ] PostgreSQL FTS 搜索端点（`/v1/knowledge/search`）
- [ ] 引用解析：import 时自动解析 Markdown 链接 → EntityReference
- [ ] 反向引用查询（`GET /v1/knowledge?ref={fqn}`）
- [ ] index.md 自动生成（export 时）
- [ ] OKF bundle import（目录树 → 批量 KnowledgeArticle）
- [ ] OKF bundle export（批量 KnowledgeArticle → tar.gz 目录树）

#### Phase 3（语义搜索——与 AI Agent 集成并行）

**目标**：pgvector 语义搜索 + Agent SDK 知识查询。

- [ ] embedding 列启用（pgvector）
- [ ] 写入时自动生成 embedding（调用 LLM embedding API）
- [ ] 混合搜索：全文 + 向量（`?q=...&mode=hybrid`）
- [ ] Agent SDK 知识查询方法：`heirloom.knowledge.search(...)`
- [ ] Agent 自动知识生成：Agent 执行操作后总结经验 → draft KnowledgeArticle

#### Phase 4（知识治理——与治理与规模化并行）

**目标**：知识条目的版本化治理 + 知识图谱。

- [ ] 知识条目版本化（每次更新保存旧版本 snapshot）
- [ ] 知识条目审批流程（draft → review → published）
- [ ] 知识图谱可视化：实体 + 知识条目的引用关系图
- [ ] 知识条目质量评分（引用完整性、新鲜度、有用性）

### 7. 设计取舍与边界

#### 7.1 为什么不做 Wiki 式协作编辑

`body` 是纯文本字段——不内嵌实时协作、评论、@提及等社交功能。Heirloom 不是 Confluence。
知识的协作编辑通过外部工具（Obsidian、VS Code），Heirloom 负责管理、索引、治理。

#### 7.2 为什么 body 不是 JSONB

body 是完整 Markdown 文本（TEXT 列），而非结构化 JSON。理由：
- OKF 兼容性：OKF 文件就是 Markdown，json 化会丢失格式信息
- 人类可读性：直接 SELECT body 返回完整的可渲染内容
- LLM 友好：Agent 拿到的是纯 Markdown，无需反序列化

frontmatter 字段单独解析到 `frontmatter` JSONB 列，用于结构化查询。

#### 7.3 为什么不做独立的知识图谱存储

当前 `references` 是 JSONB 数组——不引入独立的图存储（如 Neo4j）。Phase 4 可考虑。
JSONB 足以支撑反向引用查询（GIN index），且与现有存储架构一致。

#### 7.4 与 GlossaryTerm 的区别

| | GlossaryTerm（元数据层，Phase 2） | KnowledgeArticle（知识层，Phase 0.5） |
|---|---|---|
| 粒度 | 一个术语定义（一句话 + 同义词） | 一篇知识文档（可长可短，含上下文） |
| 结构化程度 | 高（固定字段：term, definition, synonyms） | 低（自由 Markdown body） |
| 引用方向 | 术语 → 打标签到实体 | 知识条目 → 引用实体 |
| 使用场景 | 「这个列存的是什么？」 | 「为什么这个列被定义成这样？」 |

两者互为补充：GlossaryTerm 给实体贴标签，KnowledgeArticle 给实体写文档。

#### 7.5 显式非目标

- 不做富文本编辑器（WYSIWYG）——Markdown 即可
- 不做实时协作（OT/CRDT）
- 不做知识条目之间的图谱计算（如 PageRank）
- 不做知识条目的自动翻译/本地化
- 不做 AI 自动摘要生成（属于 Agent SDK 使用场景，不属于平台能力）

---

## 后果

### 积极

- **填补架构缺口**：从两层扩展为三层，形成完整的「元数据 → 语义 → 知识」体系
- **OKF 对齐**：import/export 能力使 Heirloom 与 OKF 生态互通，降低采纳门槛
- **Agent 赋能**：AI Agent 不仅能安全操作数据，还能理解业务上下文
- **零侵入**：知识层作为独立模块，不修改现有元数据层和语义层的任何代码
- **复用基础设施**：EntityRepository、EntityResource、ChangeEventInterceptor、Authorizer 全部复用

### 消极

- **引用完整性是软的**：EntityReference 存的是 FQN 字符串，目标实体被删除时不会自动清退引用。需要定期扫描 broken references 并在 UI 中提示
- **embedding 成本**：Phase 3 的语义搜索需要 LLM API 调用生成 embedding，增加运维成本
- **OKF import 的 link 解析是尽力而为的**：Markdown 链接可能指向不存在的实体，import 时只能标记为未解析引用

---

## 备选方案

### 方案 A：多实体模型（KnowledgeArticle、BusinessRule、DecisionRecord 分别为独立 Entity）

**放弃理由**：与 OKF 的「一个 type 字段区分一切」哲学相悖。多实体模型会增加 API 表面积、
搜索复杂度（跨实体查询），且每新增一个知识类型就需要修改 EntityRegistry、数据库迁移、
新建 Resource。当前无法预测所有知识类型——过早分类会限制演化。

### 方案 B：文件系统存储（body 存为文件，数据库只存元数据索引）

**放弃理由**：Heirloom 的核心价值之一是统一的存储、查询、审计、权限。文件系统存储会：
- 绕过 EntityRepository 的事务和审计
- 失去全文检索能力（或需要外部搜索引擎）
- 引入文件系统权限问题
- 无法通过 SQL 做复杂查询

OKF 的文件格式在 export 时体现，内部存储仍然走数据库。

### 方案 C：嵌入现有 GlossaryTerm 扩展

**放弃理由**：GlossaryTerm 是一个严格结构的术语定义（term + definition + synonyms），
不适合承载长文档、Markdown body、引文等知识内容。强行扩展会破坏 GlossaryTerm 的简洁性。

---

## 参考

- OKF v0.1 规范：https://okf.md/spec/
- OKF 源码：https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md
- ADR-011: EntityRegistry
- ADR-012: HeirloomEntity
- ADR-014: EntityResource
- ADR-020: FQN 统一命名体系
- ADR-024: Metadata Entity Models (JPA + JSONB)
- Heirloom Architecture Redesign Spec (2026-06-21)
