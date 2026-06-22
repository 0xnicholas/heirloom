# Spec: Knowledge Base Module — Phase 0.5 Implementation

**日期**: 2026-06-22
**关联 ADR**: 032、032b、033、034、035
**范围**: Phase 0.5 — 知识源注册 + 文件同步 + 知识条目只读查询

---

## 1. Scope (Phase 0.5)

### In scope

| 能力 | 说明 |
|------|------|
| KnowledgeSource CRUD | 注册/管理文件目录知识源 |
| KnowledgeSyncEngine | 扫描目录 → 解析 frontmatter → 写入 knowledge_articles |
| KnowledgeArticle 只读查询 | 按 ID / FQN / 列表 查询已索引的知识条目 |
| 文件变更检测 | SHA-256 hash 比对，增量同步 |
| 手动触发同步 | `POST /v1/knowledge/sources/{id}/sync` |
| 变更审计 | ChangeEventInterceptor 自动记录 |
| 基础错误处理 | 缺失 type、frontmatter 解析失败 |

### Out of scope (Phase 1+)

| 能力 | 归属 |
|------|------|
| 全文搜索 (`/v1/knowledge/search`) | Phase 1 |
| 引用解析 (Markdown link → EntityReference) | Phase 1 |
| Git webhook 自动同步 | Phase 1 |
| index.md / log.md 自动生成 | Phase 1 |
| 管道 A/B/C (导入/自举/反哺) | Phase 0.5 (管道 B 基础) / Phase 1+ |
| 图遍历 / 覆盖分析 | Phase 1 |
| 质量评分 | Phase 1 |
| 生命周期审批 | Phase 2 |
| Agent SDK | Phase 3 |
| pgvector embedding | Phase 3 |

---

## 2. File Structure

```
heirloom-server/src/
├── main/java/com/heirloom/
│   ├── knowledge/
│   │   ├── domain/
│   │   │   ├── KnowledgeSource.java          # JPA entity
│   │   │   ├── KnowledgeArticle.java         # JPA entity
│   │   │   ├── EntityReference.java          # record
│   │   │   └── ExternalCitation.java         # record
│   │   ├── repository/
│   │   │   ├── KnowledgeSourceRepository.java    # extends EntityRepository
│   │   │   ├── KnowledgeSourceJpaRepository.java # Spring Data JPA
│   │   │   ├── KnowledgeArticleRepository.java   # extends EntityRepository
│   │   │   └── KnowledgeArticleJpaRepository.java# Spring Data JPA
│   │   ├── service/
│   │   │   ├── KnowledgeSyncService.java     # sync orchestrator
│   │   │   └── KnowledgeBootstrapper.java    # Phase 1 placeholder
│   │   ├── web/
│   │   │   ├── KnowledgeSourceResource.java  # REST CRUD
│   │   │   └── KnowledgeArticleResource.java # REST read-only
│   │   ├── sync/
│   │   │   ├── KnowledgeSyncEngine.java      # core sync logic
│   │   │   ├── FileScanner.java              # directory walker + hasher
│   │   │   ├── FrontmatterParser.java        # YAML frontmatter extraction
│   │   │   ├── SyncDiff.java                 # record
│   │   │   └── SyncReport.java               # result report
│   │   └── exception/
│   │       ├── SyncException.java
│   │       └── FrontmatterParseException.java
│   │
│   └── entity/
│       └── EntityRegistry.java               # 新增 KNOWLEDGE_SOURCE, KNOWLEDGE_ARTICLE 常量
│
└── main/resources/db/migration/
    └── V3__knowledge_base.sql                # knowledge_sources + knowledge_articles
```

---

## 3. Class Specifications

### 3.1 KnowledgeSource

```java
@Entity @Table(name = "knowledge_sources")
public class KnowledgeSource implements HeirloomEntity {
    // Fields: id, name, fullyQualifiedName, sourceType, path, branch,
    //         config (JSONB), schedule, status, description, owner,
    //         version, changeHash, deleted, createdAt, updatedAt
    
    // HeirloomEntity methods:
    //   getEntityType() → "knowledgeSource"
    
    // KnowledgeSource-specific:
    //   getSourceType() → "directory" | "git-repo"
    //   getPath()       → "/data/knowledge/" 或 git URL
}
```

**FQN**: `knowledgeSource.{name}`  
**JPA**: `KnowledgeSourceJpaRepository extends JpaRepository<KnowledgeSource, Long>`  
**Repository**: `KnowledgeSourceRepository extends EntityRepository<KnowledgeSource>`  
**Resource**: `KnowledgeSourceResource extends EntityResource<KnowledgeSource>`  
  - `POST /v1/knowledge/sources` — create  
  - `GET /v1/knowledge/sources` — list  
  - `GET /v1/knowledge/sources/{id}` — get  
  - `GET /v1/knowledge/sources/name/{fqn}` — get by FQN  
  - `PUT /v1/knowledge/sources/{id}` — update  
  - `DELETE /v1/knowledge/sources/{id}` — delete  
  - `POST /v1/knowledge/sources/{id}/sync` — trigger sync

### 3.2 KnowledgeArticle

```java
@Entity @Table(name = "knowledge_articles")
public class KnowledgeArticle implements HeirloomEntity {
    // Phase 0.5 fields: id, name, fullyQualifiedName, filePath, fileHash,
    //   sourceFqn, type, domain, title, description, resource,
    //   body, frontmatterRaw, frontmatter (JSONB), tags (JSONB),
    //   references (JSONB), citations (JSONB),
    //   author, owner, okfVersion,
    //   status, syncStatus, syncError, lastSyncedAt,
    //   version, changeHash, deleted, createdAt, updatedAt
    
    // HeirloomEntity methods:
    //   getEntityType() → "knowledgeArticle"
}
```

**FQN**: `knowledge.{sourceName}.{filePathWithoutMd}`  
  - 例: `knowledge.primary.tables.orders` ← 文件 `tables/orders.md`, source `primary`

**JPA**: `KnowledgeArticleJpaRepository extends JpaRepository<KnowledgeArticle, Long>`  
  - `findByFullyQualifiedName(String fqn)`  
  - `findBySourceFqnAndFilePath(String sourceFqn, String filePath)`  
  - `findBySourceFqnAndDeletedFalse(String sourceFqn)`  

**Repository**: `KnowledgeArticleRepository extends EntityRepository<KnowledgeArticle>`  
  - `syncUpsert(KnowledgeArticle)` — 同步专用 upsert  
  - `findByFilePath(sourceFqn, filePath)`  
  - `getIndexedFileHashes(sourceFqn)` → Map<String, String>

**Resource**: `KnowledgeArticleResource extends EntityResource<KnowledgeArticle>`  
  - `GET /v1/knowledge` — list (只读)  
  - `GET /v1/knowledge/{id}` — get by ID  
  - `GET /v1/knowledge/name/{fqn}` — get by FQN  
  - **不提供** POST / PUT / DELETE — 知识条目由同步引擎管理

### 3.3 FileScanner

```java
public class FileScanner {
    // scan(Path root) → Map<String, String>  // filePath → SHA-256 hash
    //   跳过 index.md, log.md, 隐藏文件, node_modules/, target/
    //   只扫描 .md 文件
    //   如果 root 不是目录 → throw SyncException
}
```

### 3.4 FrontmatterParser

```java
public class FrontmatterParser {
    // parse(String fileContent) → ParseResult
    //   ParseResult { frontmatter: Map, body: String, errors: List<ParseError> }
    //
    // 解析逻辑:
    //   1. 检测开头的 "---" → 提取 YAML 块
    //   2. 无 frontmatter → MISSING_TYPE error (不抛异常, 记录在 errors 中)
    //   3. YAML 解析失败 → PARSE_ERROR
    //   4. type 缺失 → MISSING_TYPE
    //   5. 提取已知字段: type, title, description, tags, resource, timestamp, status
    //   6. 其他字段 → x_ 前缀保留
    //
    // normalizeTags(Object raw) → List<String>
    //   tags: sales → ["sales"]
    //   tags: [a, b] → ["a", "b"]
    //   tags: null → []
    //
    // deriveTitle(String filePath) → String
    //   "customer-segmentation-guide.md" → "Customer Segmentation Guide"
}
```

### 3.5 SyncDiff

```java
public record SyncDiff(
    List<String> newFiles,
    List<String> changedFiles,
    List<String> removedFiles,
    List<String> unchangedFiles,
    List<String> recreatedFiles,    // 删除后重建的同名文件
    int total
) {
    public boolean hasChanges()
}
```

### 3.6 SyncReport

```java
public class SyncReport {
    // sourceFqn, status (IN_PROGRESS/COMPLETED/FAILED),
    // startedAt, completedAt, durationMs,
    // totalFiles, created, updated, removed, skipped, errors,
    // errorDetails: List<SyncError> { filePath, error, errorType }
    
    // hasErrors(), hasChanges()
}
```

### 3.7 KnowledgeSyncEngine

```java
@Service
public class KnowledgeSyncEngine {
    // sync(String sourceFqn) → SyncReport
    //
    // 算法:
    //   1. 获取 KnowledgeSource
    //   2. FileScanner.scan() → 文件 hash map
    //   3. KnowledgeArticleRepository.getIndexedFileHashes() → DB 索引
    //   4. 计算 SyncDiff
    //   5. 处理 newFiles + changedFiles + recreatedFiles (逐个):
    //      a. 读取文件内容
    //      b. FrontmatterParser.parse() → ParseResult
    //      c. 构建 KnowledgeArticle
    //      d. KnowledgeArticleRepository.syncUpsert()
    //      e. 记录成功/失败
    //   6. 处理 removedFiles → 标记 deleted = true
    //   7. 返回 SyncReport
}
```

### 3.8 EntityRegistry 新增

```java
public static final String KNOWLEDGE_SOURCE  = "knowledgeSource";
public static final String KNOWLEDGE_ARTICLE = "knowledgeArticle";
```

---

## 4. Database Migration (V3)

```sql
-- V3__knowledge_base.sql

CREATE TABLE IF NOT EXISTS knowledge_sources (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_type VARCHAR(64) NOT NULL,
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

CREATE TABLE IF NOT EXISTS knowledge_articles (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256) NOT NULL,
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  file_path VARCHAR(1024) NOT NULL,
  file_hash VARCHAR(64) NOT NULL,
  source_fqn VARCHAR(512) NOT NULL,
  type VARCHAR(128) NOT NULL,
  domain VARCHAR(128) DEFAULT 'default',
  title VARCHAR(512),
  description VARCHAR(1024),
  resource VARCHAR(2048),
  body TEXT,
  frontmatter_raw TEXT,
  frontmatter JSONB NOT NULL DEFAULT '{}',
  tags JSONB NOT NULL DEFAULT '[]',
  references_jsonb JSONB NOT NULL DEFAULT '[]',
  citations_jsonb JSONB NOT NULL DEFAULT '[]',
  author VARCHAR(256),
  owner VARCHAR(256),
  okf_version VARCHAR(16) DEFAULT '0.1',
  status VARCHAR(32) DEFAULT 'published',
  sync_status VARCHAR(32) DEFAULT 'OK',
  sync_error TEXT,
  last_synced_at TIMESTAMPTZ,
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 5. Test Scenarios

### 5.1 KnowledgeSourceRepositoryTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | 创建 KnowledgeSource（directory 类型） | 持久化，FQN = "knowledgeSource.test-src"，version=1 |
| 2 | 创建时 name 为空 | 抛出 Bean Validation 异常 |
| 3 | 按 FQN 查询 | 返回正确实体 |
| 4 | 更新 path 字段 | version 递增 |
| 5 | 软删除 | deleted=true，但 findById 仍可访问 |

### 5.2 KnowledgeArticleRepositoryTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | syncUpsert 新条目 | INSERT，FQN 按模板生成 |
| 2 | syncUpsert 已有条目（同 filePath + sourceFqn） | UPDATE，version 递增，hash 更新 |
| 3 | findByFilePath | 找到唯一实体 |
| 4 | getIndexedFileHashes | 返回 Map<filePath, fileHash> |
| 5 | FQN 生成正确 | `knowledge.primary.tables.orders` |

### 5.3 FrontmatterParserTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | 合法 frontmatter（含 type） | ParseResult.frontmatter 包含所有已知字段 |
| 2 | 缺失 type | ParseResult.errors 含 MISSING_TYPE |
| 3 | 无 frontmatter（纯 Markdown） | ParseResult.errors 含 MISSING_TYPE |
| 4 | 空 frontmatter（只有 `---`） | ParseResult.errors 含 MISSING_TYPE |
| 5 | tags 为字符串 `"sales"` | normalizeTags → ["sales"] |
| 6 | tags 为数组 `[a, b]` | normalizeTags → ["a", "b"] |
| 7 | 自定义扩展字段 `owner: team` | frontmatter["x_owner"] = "team" |
| 8 | body 正确提取（frontmatter 之后的内容） | body = "## Schema\n..." |
| 9 | 文件名推导 title | "my-table.md" → "My Table" |
| 10 | YAML 语法错误 | ParseResult.errors 含 PARSE_ERROR |

### 5.4 FileScannerTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | 扫描含 .md 文件的目录 | 返回 Map<path, hash> |
| 2 | 跳过 index.md 和 log.md | 不包含保留文件名 |
| 3 | 跳过非 .md 文件 | 只返回 .md |
| 4 | 空目录 | 返回空 Map |
| 5 | 路径不是目录 | throw SyncException |

### 5.5 KnowledgeSyncEngineTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | 首次同步（空 DB，有文件） | created = 文件数 |
| 2 | 无变更同步（hash 一致） | skipped = 文件数，created=updated=0 |
| 3 | 文件内容变更 | updated = 1，hash 更新 |
| 4 | 文件删除 | removed = 1，deleted=true |
| 5 | 删除后重建同名文件 | recreated = 1（syncUpsert 而非 create），version 递增 |
| 6 | 文件缺失 type | errors = 1，syncStatus = MISSING_TYPE |
| 7 | 前端解析失败 | errors = 1，其他文件正常处理 |

### 5.6 Integration Test

| # | 场景 | 预期 |
|---|------|------|
| 1 | 端到端：注册 source → 创建 .md 文件 → 触发同步 → API 查询 | 通过 API 可查到已索引的知识条目 |
| 2 | ChangeEventInterceptor 记录审计事件 | event_log 中有 CREATE/UPDATE 事件 |
| 3 | KnowledgeArticleResource 拒绝 POST | 405 Method Not Allowed |

---

## 6. Acceptance Criteria

1. **KnowledgeSource CRUD 可用**：可以通过 API 创建、查询、更新、删除知识源
2. **手动同步可用**：`POST /v1/knowledge/sources/{id}/sync` 返回 SyncReport
3. **增量同步正确**：第二次同步时，未变更文件被跳过（skipped count）
4. **知识条目只读 API 可用**：`GET /v1/knowledge` 返回已索引条目
5. **错误文件不阻塞同步**：缺失 type 的文件产生 error，其他文件正常处理
6. **审计事件自动记录**：所有变更写入 event_log
7. **所有单元测试通过**
8. **集成测试通过**
