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

OKF（Open Knowledge Framework）提供了一个参考格式：目录树 + Markdown + YAML frontmatter。

**关键前提**：知识的主要生产方式来自文件——数据团队在 monorepo 的 `knowledge/` 目录中编写 Markdown 文档，用 Git 管理版本。Heirloom 的角色不是知识编辑器，而是**发现、索引、搜索、服务**这些知识文件。

---

## 决策

### 1. 三层架构中引入知识层

```
┌──────────────────────────────────────────────────────────┐
│ 3. 业务知识层 (Knowledge Layer)         ← NEW            │
│    知识条目（自动从文件索引）                             │
│    真相源：文件系统 (Git repo)                            │
│    索引层：PostgreSQL（元数据 + body 副本 + pgvector）    │
│    搜索：全文检索 + 语义搜索 (Phase 3)                    │
│    格式：原生 OKF 兼容（文件即 OKF bundle）               │
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

### 2. 核心架构：文件是真相源，数据库是索引层

```
┌─────────────────────────────────────────────────────────┐
│                   Git Repository                         │
│  knowledge/                                              │
│  ├── tables/                                             │
│  │   ├── orders.md        ← 人类用 VS Code / Obsidian 写 │
│  │   └── customers.md                                    │
│  ├── playbooks/                                          │
│  │   └── incident-response.md                            │
│  ├── metrics/                                            │
│  │   └── revenue-ltv.md                                  │
│  ├── index.md                                            │
│  └── log.md                                              │
│                                                          │
│  ▲ 作者在这里编辑（git push）                              │
│  │                                                        │
│  │  KnowledgeSyncEngine 同步                              │
│  │  （复用 Discovery Engine 的拓扑/扫描模式）              │
│  │                                                        │
│  ▼                                                        │
├─────────────────────────────────────────────────────────┤
│                   PostgreSQL                             │
│  knowledge_articles                                      │
│  ├── fqn                    # knowledge.tables.orders    │
│  ├── type                   # "BigQuery Table"           │
│  ├── file_path              # knowledge/tables/orders.md │
│  ├── file_hash              # SHA-256 检测文件变更        │
│  ├── title                  # frontmatter.title          │
│  ├── description            # frontmatter.description    │
│  ├── body                   # 完整 Markdown（搜索索引）   │
│  ├── body_frontmatter       # 解析后的 YAML → JSONB      │
│  ├── tags                   # JSONB                      │
│  ├── references             # 解析出的 Heirloom 实体引用  │
│  ├── embedding              # pgvector (Phase 3)         │
│  └── ...                    # 标准 HeirloomEntity 字段    │
│                                                          │
│  ▲ Heirloom API / Agent SDK 在这里消费                    │
└─────────────────────────────────────────────────────────┘
```

**数据流是单向的**：文件 → 同步 → 数据库。Heirloom 不在数据库中创建或编辑知识条目——所有变更发生在文件中，Heirloom 负责把变更索引到数据库。

### 3. 实体设计

#### 3.1 KnowledgeSource ——文件目录配置（类似 DiscoverySource）

```java
@Entity
@Table(name = "knowledge_sources")
public class KnowledgeSource implements HeirloomEntity {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String fullyQualifiedName;          // "knowledgeSource.{name}"
    
    private String sourceType;                  // "directory" | "git-repo"
    private String path;                        // "/path/to/knowledge/" 或 git URL
    private String branch = "main";             // git branch（git-repo 模式下）
    @Column(columnDefinition = "jsonb")
    private String config = "{}";               // 扩展配置（file glob、排除规则等）
    
    private String schedule = "on-commit";      // "manual" | "on-commit" | "cron:... "
    private String status = "ACTIVE";
    private String description;
    private String owner;
    
    // 标准 HeirloomEntity 字段
    private Long version = 1L;
    private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt, updatedAt;
}
```

**FQN 模式**：`knowledgeSource.{name}`

#### 3.2 KnowledgeArticle ——索引条目（类似 DiscoveryReport）

```java
@Entity
@Table(name = "knowledge_articles")
public class KnowledgeArticle implements HeirloomEntity {
    @Id @GeneratedValue
    private Long id;

    // === 文件指针 ===
    @Column(nullable = false, length = 1024)
    private String filePath;                    // "knowledge/tables/orders.md"
    
    @Column(nullable = false, length = 64)
    private String fileHash;                    // SHA-256，检测文件变更
    
    @Column(nullable = false, length = 512)
    private String sourceFqn;                   // 指向所属 KnowledgeSource 的 FQN

    // === OKF 兼容字段（从 frontmatter 解析） ===
    @Column(nullable = false, length = 128)
    private String type;                        // OKF 自由类型，如 "Playbook", "Metric"
    
    private String title;                       // frontmatter.title
    private String description;                 // frontmatter.description

    /**
     * OKF resource — the canonical URI of the underlying asset.
     * Two forms are supported:
     *   - External URI: https://console.cloud.google.com/bigquery?...
     *   - Heirloom FQN: @metadata_tables.acme.sales.orders
     * The @ prefix triggers automatic EntityReference resolution during sync.
     */
    @Column(length = 2048)
    private String resource;
    
    /** 完整 Markdown 正文（去 frontmatter）——用于搜索和 API 返回 */
    @Column(columnDefinition = "text")
    private String body;
    
    /** 完整的 frontmatter 原始文本（保留原样，用于 round-trip） */
    @Column(columnDefinition = "text")
    private String frontmatterRaw;
    
    /** 解析后的 frontmatter JSONB */
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> frontmatter = new HashMap<>();
    
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    // === Heirloom 增强字段 ===
    
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<EntityReference> references = new ArrayList<>();
    
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<ExternalCitation> citations = new ArrayList<>();
    
    /** pgvector 向量——Phase 3 */
    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;
    
    private String domain = "default";
    private String author;
    private String okfVersion = "0.1";

    // === 同步元数据 ===
    private Instant lastSyncedAt;                // 上次同步时间
    private String syncStatus = "OK";            // "OK" | "PARSE_ERROR" | "MISSING_TYPE"
    private String syncError;                    // 同步错误信息

    // === 标准 HeirloomEntity 字段 ===
    private String fullyQualifiedName;
    private String owner;
    private Long version = 1L;
    private String changeHash;
    private Boolean deleted = false;
    private Instant createdAt, updatedAt;
}
```

#### 3.3 引用组件

```java
public record EntityReference(
    String fqn,          // 被引用实体的 FQN
    String entityType,   // 被引用实体的类型
    String label,        // 引用角色描述
    String sourceText    // 在 body 中的原始 Markdown 链接文本
) {}

public record ExternalCitation(
    String url,
    String title,
    String description
) {}
```

#### 3.4 FQN 模式

```
knowledge.{source}.{file-path-without-md}

例如：
  knowledge.primary.tables.orders        ← 文件 knowledge/tables/orders.md
  knowledge.primary.playbooks.incident    ← 文件 knowledge/playbooks/incident-response.md
```

其中 `{source}` 是 KnowledgeSource 的名称，`{file-path-without-md}` 是去掉 `.md` 后缀的文件相对路径，路径分隔符 `/` 转换为 `.`。

#### 3.5 OKF Frontmatter 字段映射规范

OKF 定义了 6 个约定字段。Heirloom 的映射如下：

| OKF 字段 | 必填 | Heirloom 字段 | 解析规则 |
|----------|------|--------------|---------|
| `type` | ✅ | `KnowledgeArticle.type` | 即 OKF 唯一必填字段。存储原始值，不规范化 |
| `title` | 否 | `KnowledgeArticle.title` | 缺失时从文件名推导（`"customer-segmentation-guide"` → `"Customer Segmentation Guide"`） |
| `description` | 否 | `KnowledgeArticle.description` | 截断到 1024 字符 |
| `tags` | 否 | `KnowledgeArticle.tags` | 字符串自动包装为单元素数组 |
| `resource` | 否 | `KnowledgeArticle.resource` | 见下方解析规则 |
| `timestamp` | 否 | `KnowledgeArticle.updatedAt` | ISO 8601 → `Instant`。如果不在 frontmatter 中，则使用文件修改时间 |

**`resource` 字段的特殊解析**：

```java
/**
 * 解析 frontmatter.resource 字段。
 * 支持三种格式：
 * 
 *   1. @FQN — Heirloom 内部引用
 *      resource: @metadata_tables.acme.sales.orders
 *      → 自动创建 EntityReference(fqn="...", entityType="table", label="Canonical resource")
 * 
 *   2. http(s):// URL — 外部资源
 *      resource: https://console.cloud.google.com/bigquery?p=acme&d=sales&t=orders
 *      → 存储为 ExternalCitation
 * 
 *   3. 自定义 URI scheme — 原样保留
 *      resource: bigquery://project.dataset.table
 *      → 存储为字符串，不做解析
 */
public void resolveResource(String resourceValue, KnowledgeArticle article) {
    if (resourceValue == null) return;
    
    article.setResource(resourceValue);
    
    if (resourceValue.startsWith("@")) {
        // Heirloom FQN 引用
        String fqn = resourceValue.substring(1);
        EntityReference ref = resolveEntityReference(fqn);
        if (ref != null) {
            // 添加到 references（如果还没有）
            article.getReferences().add(ref);
        }
    } else if (resourceValue.startsWith("http://") || resourceValue.startsWith("https://")) {
        // 外部 URL
        article.getCitations().add(new ExternalCitation(resourceValue, "Canonical resource", null));
    }
    // 其他 URI scheme 原样保留
}
```

**完整 frontmatter 示例**：

```yaml
---
type: BigQuery Table
title: Customer Orders
description: One row per completed customer order across all channels.
resource: @metadata_tables.acme.sales.orders
tags: [sales, orders, revenue]
timestamp: 2026-05-28T14:30:00Z
owner: data-team@acme.com            # 自定义扩展字段
freshness_sla: 30m                   # 自定义扩展字段
---
```

### 4. 同步引擎（KnowledgeSyncEngine）

复用 Discovery Engine 的拓扑模式。流程如下：

```
KnowledgeSyncEngine.sync("knowledgeSource.primary")
│
├─ 1. 扫描文件系统
│   ├─ 遍历 path 下的所有 .md 文件（跳过 index.md、log.md）
│   ├─ 对每个文件计算 SHA-256 hash
│   └─ 与数据库中已索引的 fileHash 比较
│
├─ 2. 增量处理
│   ├─ 新文件 → 创建 KnowledgeArticle
│   ├─ 已变更文件（hash 不同）→ 更新 KnowledgeArticle
│   ├─ 已删除文件（DB 中有但文件系统不存在）→ 标记 deleted = true
│   └─ 未变更文件 → 跳过
│
├─ 3. 解析每个文件
│   ├─ 提取 YAML frontmatter
│   │   ├─ 必填字段：type（缺失则标记 syncStatus = "MISSING_TYPE"，跳过索引但仍记录文件存在）
│   │   └─ 可选字段：title、description、tags、resource、timestamp
│   ├─ 提取 body（frontmatter 之后的内容）
│   ├─ 解析 Markdown 链接 → EntityReference（如果链接目标 FQN 在 EntityRegistry 中存在）
│   └─ 解析 `# Citations` 区块 → ExternalCitation
│
├─ 4. 持久化
│   ├─ KnowledgeArticle 写入数据库
│   ├─ ChangeEventInterceptor 自动审计（CREATE/UPDATE/DELETE 事件）
│   └─ 更新 lastSyncedAt
│
└─ 5. 生成变更报告（SyncReport）
    ├─ created: 3
    ├─ updated: 1
    ├─ deleted: 0
    ├─ skipped (unchanged): 12
    └─ errors: [{file: "bad.md", error: "Missing type field"}]
```

#### 4.1 同步触发方式

| 模式 | 机制 | 适用场景 |
|------|------|---------|
| `manual` | API 调用 `POST /v1/knowledge/sources/{fqn}/sync` | 开发阶段、一次性导入 |
| `on-commit` | Git webhook → Heirloom 接收 POST → 触发 sync | monorepo CI/CD 集成 |
| `cron:*/5 * * * *` | Spring `@Scheduled` 定时扫描 | 简单部署、目录文件系统 |

`on-commit` 模式通过 GitHub/GitLab webhook 接收 push 事件，Heirloom 收到后执行 `git pull`（如果 KnowledgeSource 配置了 git repo URL），然后运行 sync。

### 5. 数据库迁移

```sql
-- V3__knowledge_base.sql

-- 5a. KnowledgeSource —— 文件目录/仓库配置
CREATE TABLE IF NOT EXISTS knowledge_sources (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_type VARCHAR(64) NOT NULL,           -- 'directory' | 'git-repo'
  path VARCHAR(1024) NOT NULL,
  branch VARCHAR(256) DEFAULT 'main',
  config JSONB NOT NULL DEFAULT '{}',
  schedule VARCHAR(64) DEFAULT 'manual',
  status VARCHAR(32) DEFAULT 'ACTIVE',
  description TEXT,
  owner VARCHAR(256),
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 5b. KnowledgeArticle —— 从文件同步的知识索引条目
CREATE TABLE IF NOT EXISTS knowledge_articles (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,

  -- 文件指针
  file_path VARCHAR(1024) NOT NULL,
  file_hash VARCHAR(64) NOT NULL,             -- SHA-256
  source_fqn VARCHAR(512) NOT NULL,

  -- OKF 兼容字段
  type VARCHAR(128) NOT NULL,
  domain VARCHAR(128) DEFAULT 'default',
  title VARCHAR(512),
  description VARCHAR(1024),
  resource VARCHAR(2048),                     -- OKF resource: @FQN 引用或外部 URL

  -- 正文（搜索索引副本）
  body TEXT,
  frontmatter_raw TEXT,
  frontmatter JSONB NOT NULL DEFAULT '{}',

  -- 标签、引用
  tags JSONB NOT NULL DEFAULT '[]',
  references_jsonb JSONB NOT NULL DEFAULT '[]',
  citations_jsonb JSONB NOT NULL DEFAULT '[]',

  -- 向量（Phase 3）
  embedding VECTOR(1536),

  -- 同步元数据
  sync_status VARCHAR(32) DEFAULT 'OK',
  sync_error TEXT,
  last_synced_at TIMESTAMPTZ,

  -- 治理
  author VARCHAR(256),
  owner VARCHAR(256),
  okf_version VARCHAR(16) DEFAULT '0.1',

  -- 标准 HeirloomEntity 字段
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 全文检索索引（Phase 1）
CREATE INDEX IF NOT EXISTS idx_ka_fts
  ON knowledge_articles
  USING GIN (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(description,'') || ' ' || coalesce(body,'')));

-- 按文件路径去重查询
CREATE INDEX IF NOT EXISTS idx_ka_file_path ON knowledge_articles(source_fqn, file_path);

-- 按类型和 domain 聚合
CREATE INDEX IF NOT EXISTS idx_ka_type ON knowledge_articles(type, domain);

-- 反向引用查询
CREATE INDEX IF NOT EXISTS idx_ka_refs_fqn
  ON knowledge_articles
  USING GIN ((references_jsonb));

-- pgvector 索引（Phase 3）
-- CREATE INDEX IF NOT EXISTS idx_ka_embedding
--   ON knowledge_articles
--   USING ivfflat (embedding vector_cosine_ops)
--   WITH (lists = 100);
```

### 6. API 设计

#### 6.1 KnowledgeSource CRUD

```
GET    /v1/knowledge/sources                    # 列出所有知识源
POST   /v1/knowledge/sources                    # 注册新知识源
GET    /v1/knowledge/sources/{id}               # 获取知识源详情
PUT    /v1/knowledge/sources/{id}               # 更新配置
DELETE /v1/knowledge/sources/{id}               # 移除知识源
POST   /v1/knowledge/sources/{id}/sync          # 手动触发同步
```

#### 6.2 KnowledgeArticle 查询（只读）

```
GET    /v1/knowledge                            # 列出知识条目
GET    /v1/knowledge/{id}                       # 按 ID 获取（含完整 body）
GET    /v1/knowledge/name/{fqn}                 # 按 FQN 获取
```

**不提供 CREATE / UPDATE / DELETE 端点**——知识条目由同步引擎自动管理。文件的增删改通过 Git / 文件系统操作，不在 Heirloom API 中进行。

#### 6.3 搜索端点

```
GET /v1/knowledge/search
  ?q={query}                                    # 全文搜索关键词
  &type={type}                                  # 按 OKF type 过滤
  &tags={tag1},{tag2}                           # 按标签过滤
  &domain={domain}                              # 按 domain 过滤
  &source={sourceFqn}                           # 按来源过滤
  &ref={fqn}                                    # 反向引用：找出引用此实体的所有知识条目
  &mode=fts|hybrid                              # fts (Phase 1) 或 hybrid (Phase 3)
  &limit=20&offset=0
```

#### 6.4 OKF 导出（只读）

```
GET /v1/knowledge/export/okf
  ?source={sourceFqn}                           # 限制导出来源
  &type={type}                                  # 限制导出类型
```

导出时不从数据库生成——直接从文件系统读取，保持原始格式。Heirloom 只是把文件系统里的目录打包成 tar.gz 返回。这样保证导出的 OKF bundle 与作者的原始文件完全一致。

### 7. 与现有系统集成

#### 7.1 复用 Discovery Engine 模式

`KnowledgeSyncEngine` 不是另一个引擎——它**复用 Discovery Engine 的拓扑/扫描基础设施**：

| Discovery 概念 | Knowledge 对应 |
|---------------|---------------|
| `DiscoverySource` | `KnowledgeSource` |
| `DiscoveryReport` | `KnowledgeArticle`（索引结果） |
| `DiscoveryRunner` 扫描数据库 | `KnowledgeSyncEngine` 扫描文件 |
| `DiscoveryService` | `KnowledgeSyncService` |
| `Extractor` 读取 DB schema | `FileScanner` 读取 .md 文件 |
| `InferenceRule` 推断 ResourceType | `FrontmatterParser` 解析 YAML |

#### 7.2 EntityRegistry 新增常量

```java
public static final String KNOWLEDGE_SOURCE  = "knowledgeSource";
public static final String KNOWLEDGE_ARTICLE = "knowledgeArticle";
```

#### 7.3 ChangeEventInterceptor

`KnowledgeArticle` 和 `KnowledgeSource` 均实现 `HeirloomEntity`，自动获得审计。

#### 7.4 Authorizer（Phase 2）

- 知识条目读取权限与语义层对齐
- 知识源管理（注册/同步）需要 admin 权限

### 8. 分阶段实施

#### Phase 0.5：知识源注册 + 手动同步

- [ ] `KnowledgeSource` Entity + Repository + Resource（标准 CRUD）
- [ ] `KnowledgeArticle` Entity + Repository + Resource（只读查询端点）
- [ ] `KnowledgeSyncEngine`：扫描本地目录 → 解析 frontmatter → 写入索引
- [ ] 数据库迁移 V3
- [ ] 手动触发同步：`POST /v1/knowledge/sources/{id}/sync`
- [ ] 文件变更检测：`fileHash` 比对，增量更新
- [ ] 单元测试 + 集成测试

#### Phase 1：搜索 + 引用 + webhook 自动同步

- [ ] PostgreSQL FTS 搜索（`/v1/knowledge/search?q=...`）
- [ ] 引用解析：扫描 body 中 Markdown 链接 → `EntityReference`
- [ ] 反向引用查询（`?ref={fqn}`）
- [ ] Git webhook 触发自动同步（`on-commit` 模式）
- [ ] OKF 导出端点（从文件系统打包 tar.gz）

#### Phase 3：语义搜索 + Agent SDK

- [ ] pgvector embedding 生成与索引
- [ ] 混合搜索：FTS + 向量（`?mode=hybrid`）
- [ ] Agent SDK：`heirloom.knowledge.search(...)`

#### Phase 4：治理

- [ ] 知识条目质量报告（引用完整性、frontmatter 规范性）
- [ ] 知识图谱可视化（实体 ←→ 知识引用关系）

### 9. 设计取舍

#### 9.1 为什么不做双向写入

文件是真相源意味着**不在 Heirloom 中编辑知识**。权衡：
- **优势**：避免了文件↔数据库同步冲突，保持了「文件即 OKF bundle」的原生性
- **代价**：用户不能通过 Web UI 创建知识条目——必须在文件中编写

未来 Phase 4+ 可考虑 Web 编辑器（写回 Git repo），但 Phase 0-3 保持单向同步。

#### 9.2 为什么 body 存两份（文件 + 数据库）

文件中的 body 是真相，数据库中的 body 是**搜索索引副本**。没有数据库副本，搜索和 API 返回需要实时读文件——性能不可控。副本通过同步引擎维护，`fileHash` 保证一致性。

#### 9.3 为什么保留 frontmatterRaw

解析 YAML 为 JSONB 用于结构化查询，但保留原始 frontmatter 文本用于 round-trip 完整性。未来如需「数据库修改 frontmatter → 写回文件」的能力，原始文本是必需的。

#### 9.4 与现有 GlossaryTerm 的区别

| | GlossaryTerm（元数据层） | KnowledgeArticle（知识层） |
|---|---|---|
| 存储 | 数据库为主 | 文件为主，数据库为索引 |
| 编辑方式 | Heirloom API | VS Code / Obsidian → Git push |
| 粒度 | 术语定义 | 长文档 |
| 引用 | 术语标签到实体 | 知识条目引用实体（Markdown 链接） |

---

## 后果

### 积极

- **零编辑门槛**：作者不需要学习 Heirloom——用 VS Code 写 Markdown 即可，和写代码一样
- **原生 OKF 兼容**：`knowledge/` 目录本身就是一个 OKF bundle，无需 import/export
- **Git 原生**：知识版本管理、code review、CI 校验全部走 Git 工作流
- **复用发现基础设施**：KnowledgeSyncEngine 与 Discovery Engine 共享拓扑/扫描模式

### 消极

- **不属于 Heirloom 管理的文件变更无法追踪**：如果有人在 Heirloom 不知道的情况下移动文件，索引变成 stale
- **大型目录扫描开销**：`knowledge/` 有上千个文件时，首次同步较慢（可通过增量 hash 比对缓解）
- **Web UI 只读**：用户不能在浏览器中新建知识条目，必须操作文件

---

## 备选方案

### 方案 A：数据库为主存储（原设计）

**放弃理由**：用户明确知识生产在文件中。且 OKF 的设计哲学就是文件即格式，数据库存储背离了这一核心价值。

### 方案 B：Elasticsearch 作为索引引擎

**放弃理由**：增加运维依赖。PostgreSQL 的 FTS + pgvector 在 Phase 0-3 的规模下（几千到几万篇文档）完全够用。Phase 4 规模扩大后可考虑。

---

## 参考

- OKF v0.1 规范：https://okf.md/spec/
- ADR-019: 两阶段发现——提取→推断
- ADR-022: Discovery Topology——声明式遍历树
- ADR-011: EntityRegistry
- ADR-012: HeirloomEntity
- ADR-014: EntityResource
