# Spec: Knowledge Base Module — Phase 0.5a Core Sync Pipeline

**日期**: 2026-06-22
**关联 ADR**: 032、032b
**范围**: Phase 0.5a — 知识源 CRUD + 文件扫描 + 同步引擎 + 只读查询 API

---

## 0. Phase Split

| Phase | 内容 | 依赖 |
|-------|------|------|
| **0.5a**（本 Spec） | KnowledgeSource CRUD, 同步引擎, Article 只读查询, 变更审计 | 无 |
| **0.5b**（后续 Spec） | 管道 B（元数据自举）, `_generated/` 目录, Mustache 模板, index.md/log.md 生成, `resource`→FQN 解析, status 字段状态机 | 0.5a 完成 |

---

## 1. Scope

### In scope (0.5a)

| 能力 | 说明 |
|------|------|
| KnowledgeSource CRUD | 注册/管理文件目录知识源 |
| FileScanner | 扫描目录，SHA-256 hash，跳过保留文件名 |
| FrontmatterParser | YAML 解析，type 必填校验，标签规范化，body 提取 |
| SyncDiff | 增量 diff（new/changed/removed/recreated/skipped） |
| KnowledgeSyncService | 协调扫描→diff→parse→upsert→report 流水线 |
| KnowledgeArticle 只读查询 | 按 ID / FQN / 列表查询 |
| 手动触发同步 | `POST /v1/knowledge/sources/{id}/sync` |
| 变更审计 | ChangeEventInterceptor 自动记录 |
| 错误容错 | 单文件解析失败不阻塞其他文件 |

### Out of scope → Phase 0.5b

`resource`→FQN 解析, `status` 字段状态机, 管道 B（自举）, Mustache 模板, `_generated/` 目录, `index.md`/`log.md` 生成

### Out of scope → Phase 1+

全文搜索, 引用解析（link→EntityReference）, Git webhook, 图遍历, 质量评分

---

## 2. File Structure

```
heirloom-server/src/
├── main/java/com/heirloom/
│   └── knowledge/
│       ├── domain/
│       │   ├── KnowledgeSource.java
│       │   ├── KnowledgeArticle.java
│       │   ├── EntityReference.java          # record
│       │   └── ExternalCitation.java         # record
│       ├── repository/
│       │   ├── KnowledgeSourceJpaRepository.java
│       │   ├── KnowledgeSourceRepository.java
│       │   ├── KnowledgeArticleJpaRepository.java
│       │   └── KnowledgeArticleRepository.java
│       ├── service/
│       │   └── KnowledgeSyncService.java     # orchestrator: Resource→Service→Engine
│       ├── web/
│       │   ├── KnowledgeSourceResource.java  # REST CRUD
│       │   └── KnowledgeArticleResource.java # REST read-only
│       ├── sync/
│       │   ├── KnowledgeSyncEngine.java      # internal component
│       │   ├── FileScanner.java
│       │   ├── FrontmatterParser.java
│       │   ├── ParseResult.java              # record
│       │   ├── ParseError.java               # record
│       │   ├── SyncDiff.java                 # record
│       │   └── SyncReport.java
│       └── exception/
│           ├── SyncException.java
│           └── FrontmatterParseException.java
│
└── main/resources/db/migration/
    └── V3__knowledge_base.sql
```

---

## 3. Class Specifications

### 3.1 KnowledgeSource

```java
@Entity @Table(name = "knowledge_sources")
public class KnowledgeSource implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    private String name;
    private String fullyQualifiedName;          // "knowledgeSource.{name}"
    private String sourceType;                  // "directory" | "git-repo"
    private String path;
    private String branch = "main";
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private String config = "{}";               // 文件 glob、排除规则（0.5a 预留，0.5b+ 启用）
    private String schedule = "manual";
    private String status = "ACTIVE";
    private String description;
    private String owner;
    // Standard HeirloomEntity: version, changeHash, deleted, createdAt, updatedAt

    public String getEntityType() { return "knowledgeSource"; }
}
```

**FQN**: `knowledgeSource.{name}`

### 3.2 KnowledgeArticle

```java
@Entity @Table(name = "knowledge_articles")
public class KnowledgeArticle implements HeirloomEntity {
    @Id @GeneratedValue private Long id;
    private String name;
    private String fullyQualifiedName;          // "knowledge.{source}.{path}"
    // File pointer
    private String filePath;
    private String fileHash;                    // SHA-256
    private String sourceFqn;
    // OKF fields (from frontmatter)
    private String type;
    private String domain = "default";
    private String title;
    private String description;
    private String resource;
    @Column(columnDefinition = "text")
    private String body;
    @Column(columnDefinition = "text")
    private String frontmatterRaw;              // raw YAML for round-trip
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> frontmatter = new HashMap<>();
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<EntityReference> references = new ArrayList<>();
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<ExternalCitation> citations = new ArrayList<>();
    // Governance
    private String author;
    private String owner;
    private String okfVersion = "0.1";
    private String status = "published";        // 0.5a: 固定 published；0.5b: 从 frontmatter 解析
    // Sync metadata
    private String syncStatus = "OK";           // OK | PARSE_ERROR | MISSING_TYPE
    private String syncError;
    private Instant lastSyncedAt;
    // Standard: version, changeHash, deleted, createdAt, updatedAt

    public String getEntityType() { return "knowledgeArticle"; }
}
```

**FQN**: `knowledge.{sourceName}.{filePathWithoutMd}`
- `{sourceName}` = `sourceFqn` 中 `.` 之后的部分（如 `knowledgeSource.primary` → `primary`）
- `{filePathWithoutMd}` = filePath 去 `.md` 后缀，`/` 替换为 `.`

### 3.3 EntityReference / ExternalCitation

```java
public record EntityReference(String fqn, String entityType, String label, String sourceText) {}
public record ExternalCitation(String url, String title, String description) {}
```

### 3.4 FileScanner

```java
public class FileScanner {
    private static final Set<String> RESERVED = Set.of("index.md", "log.md");
    private static final Pattern IGNORE = Pattern.compile("^\\.|node_modules|target");

    /**
     * Walk root directory, return relative path → SHA-256 hash.
     * Skips: reserved filenames, hidden files, node_modules/, target/.
     * Throws SyncException if root is not a directory.
     */
    public Map<String, String> scan(Path root) { ... }
}
```

### 3.5 FrontmatterParser

```java
public class FrontmatterParser {

    /**
     * Parse YAML frontmatter from Markdown content.
     * 
     * @param fileContent  raw .md file content
     * @param filePath     used for title derivation if frontmatter lacks title
     * @return ParseResult with frontmatter map, body string, raw frontmatter text, errors
     */
    public ParseResult parse(String fileContent, String filePath) { ... }

    /**
     * Normalize tags: string → ["string"], array → as-is, null → [].
     */
    public static List<String> normalizeTags(Object raw) { ... }

    /**
     * Derive title from filename: "customer-seg-guide.md" → "Customer Seg Guide".
     */
    public static String deriveTitle(String filePath) { ... }
}

public record ParseResult(
    Map<String, Object> frontmatter,    // normalized (known fields extracted, others x_-prefixed)
    String body,                         // content after closing "---"
    String frontmatterRaw,               // original YAML text (for round-trip)
    List<ParseError> errors
) {
    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean hasType() { return frontmatter.containsKey("type"); }
}

public record ParseError(String message, String errorType) {}
// errorType values: MISSING_TYPE, PARSE_ERROR, UNCLOSED_FRONTMATTER
```

**解析算法**:
1. 检测第一行 `---` → 提取 YAML 文本 → 保存为 `frontmatterRaw`
2. SnakeYAML 解析（Spring Boot 自带 `org.yaml.snakeyaml`）
3. 提取已知字段: `type`, `title`, `description`, `tags`, `resource`, `timestamp`
4. 其他字段 → `x_` 前缀保留
5. `type` 缺失 → `ParseError("MISSING_TYPE")`, 不抛异常
6. YAML 语法错误 → `ParseError("PARSE_ERROR")`
7. `body` = 第二个 `---` 之后的内容
8. 无 title → `deriveTitle(filePath)` 推导

### 3.6 SyncDiff

```java
public record SyncDiff(
    List<String> newFiles,          // on disk, not in DB
    List<String> changedFiles,      // on disk, in DB, hash differs
    List<String> removedFiles,      // in DB, not on disk
    List<String> unchangedFiles,    // hash matches → skip
    List<String> recreatedFiles,    // was removed, now recreated → syncUpsert
    int total
) {
    public boolean hasChanges() {
        return !newFiles.isEmpty() || !changedFiles.isEmpty()
            || !removedFiles.isEmpty() || !recreatedFiles.isEmpty();
    }
}
```

### 3.7 SyncReport

```java
public class SyncReport {
    private String sourceFqn;
    private String status;              // IN_PROGRESS | COMPLETED | FAILED
    private Instant startedAt, completedAt;
    private long durationMs;
    private int totalFiles, created, updated, removed, skipped, errors;
    private List<SyncError> errorDetails;

    public record SyncError(String filePath, String error, String errorType) {}

    // Factory
    public static SyncReport start(String sourceFqn) { ... }
    public void complete() { ... }
}
```

### 3.8 KnowledgeSyncEngine

```java
/**
 * Internal component — not a Spring @Service.
 * Called by KnowledgeSyncService.
 */
public class KnowledgeSyncEngine {

    private final KnowledgeArticleRepository articleRepo;

    /**
     * Core sync algorithm (synchronous — for Phase 0.5a, file counts are small).
     * Phase 0.5b may introduce SyncExecutor with thread pool.
     */
    public SyncReport sync(KnowledgeSource source) {
        // 1. FileScanner.scan(source.getPath()) → Map<filePath, hash>
        // 2. articleRepo.getIndexedFileHashes(sourceFqn) → Map<filePath, hash>
        // 3. Compute SyncDiff
        // 4. For newFiles + changedFiles + recreatedFiles:
        //    a. Read file content
        //    b. FrontmatterParser.parse(content, filePath) → ParseResult
        //    c. If ParseResult.hasType(): build KnowledgeArticle, articleRepo.syncUpsert()
        //    d. If !hasType(): build error KnowledgeArticle with syncStatus=MISSING_TYPE
        //    e. On exception: catch, record error, continue with next file
        // 5. For removedFiles: mark deleted=true
        // 6. Return SyncReport
    }
}
```

### 3.9 KnowledgeSyncService

```java
@Service
public class KnowledgeSyncService {

    private final KnowledgeSourceRepository sourceRepo;
    private final KnowledgeSyncEngine syncEngine;

    /**
     * Trigger sync for a knowledge source.
     * Returns the SyncReport directly (synchronous, no async for 0.5a).
     */
    public SyncReport sync(String sourceFqn) {
        KnowledgeSource source = sourceRepo.findByFQN(sourceFqn)
            .orElseThrow(() -> new IllegalArgumentException("Source not found: " + sourceFqn));
        return syncEngine.sync(source);
    }
}
```

### 3.10 Repository Notes

**KnowledgeSourceRepository** — standard `EntityRepository<KnowledgeSource>`:
- `setFullyQualifiedName(s)`: `"knowledgeSource." + s.getName()`
- `prepareInternal(s, isUpdate)`: no-op

**KnowledgeArticleRepository** — extends `EntityRepository<KnowledgeArticle>`:
- `setFullyQualifiedName(a)`: `"knowledge." + extractSourceName(a.getSourceFqn()) + "." + filePathToFqn(a.getFilePath())`
- `prepareInternal(a, isUpdate)`: no-op
- **`syncUpsert(KnowledgeArticle)`**: findByFilePath → if exists UPDATE else INSERT (direct JPA, not via create/update template)
- `findByFilePath(sourceFqn, filePath)`: delegate to JPA
- `getIndexedFileHashes(sourceFqn)`: `SELECT file_path, file_hash WHERE source_fqn = ? AND deleted = false`

### 3.11 Resource Notes

**KnowledgeSourceResource** extends `EntityResource<KnowledgeSource>`:
- Constructor: `super(EntityRegistry.KNOWLEDGE_SOURCE, authorizer)`
- Phase 0.5a uses `NoopAuthorizer` (no auth)
- Extra endpoint: `POST /v1/knowledge/sources/{id}/sync` → delegates to `KnowledgeSyncService.sync()`

**KnowledgeArticleResource** extends `EntityResource<KnowledgeArticle>`:
- Constructor: `super(EntityRegistry.KNOWLEDGE_ARTICLE, authorizer)`
- **Only exposes** `@GetMapping` methods (list, getById, getByFQN)
- **Does NOT override** `create()`/`update()`/`delete()` from base class → these endpoints are simply not declared with `@PostMapping`/`@PutMapping`/`@DeleteMapping` in the subclass
- The base class protected methods (`create()`, `update()`, `delete()`) exist but are unreachable via HTTP because no `@RequestMapping` maps to them

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

-- Indexes
CREATE INDEX IF NOT EXISTS idx_ka_file_path ON knowledge_articles(source_fqn, file_path);
CREATE INDEX IF NOT EXISTS idx_ka_source_fqn ON knowledge_articles(source_fqn, deleted);
```

---

## 5. Test Scenarios

### 5.1 KnowledgeSourceRepositoryTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | 创建 directory 类型 KnowledgeSource | 持久化，FQN="knowledgeSource.test-src" |
| 2 | 按 FQN 查询 | 返回正确实体 |
| 3 | 更新 path | version 递增 |
| 4 | 软删除 | deleted=true, findById 仍可访问 |

### 5.2 KnowledgeArticleRepositoryTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | syncUpsert 新条目 | INSERT, FQN 按模板生成 |
| 2 | syncUpsert 已有条目（同 filePath+sourceFqn） | UPDATE, version 递增, hash 更新 |
| 3 | findByFilePath | 找到唯一实体 |
| 4 | getIndexedFileHashes | 返回 Map<filePath, fileHash> |
| 5 | FQN 生成 | `knowledge.primary.tables.orders` |

### 5.3 FrontmatterParserTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | 合法 frontmatter（含 type, title, tags） | frontmatter 含所有字段, body 正确, errors 为空 |
| 2 | 缺失 type | errors 含 MISSING_TYPE |
| 3 | 无 frontmatter（纯 Markdown） | errors 含 MISSING_TYPE, body = 全文 |
| 4 | 空 frontmatter（只有 `---\n---`） | errors 含 MISSING_TYPE |
| 5 | `tags: sales`（字符串） | normalizeTags → ["sales"] |
| 6 | `tags: [a, b]`（数组） | normalizeTags → ["a", "b"] |
| 7 | `tags: null` | normalizeTags → [] |
| 8 | 自定义扩展 `owner: team` | frontmatter["x_owner"] = "team" |
| 9 | `title` 缺失 → deriveTitle | "my-table.md" → "My Table" |
| 10 | YAML 语法错误（非法缩进） | errors 含 PARSE_ERROR |
| 11 | frontmatterRaw 正确保存 | raw 字段含原始 YAML 文本 |

### 5.4 FileScannerTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | 扫描含 .md 的目录 | Map<path, hash> |
| 2 | 跳过 index.md, log.md | 不包含保留文件名 |
| 3 | 跳过 .txt 文件 | 只有 .md |
| 4 | 空目录 | 空 Map |
| 5 | 路径不是目录 | throw SyncException |
| 6 | 嵌套子目录中的 .md | 包含，path 为相对路径 |

### 5.5 KnowledgeSyncEngineTest

| # | 场景 | 预期 |
|---|------|------|
| 1 | 首次同步（空 DB, 2 文件） | created=2 |
| 2 | 无变更同步（hash 一致） | skipped=2, created=updated=0 |
| 3 | 文件内容变更 | updated=1 |
| 4 | 文件删除 | removed=1, deleted=true |
| 5 | 删除后重建同名文件 | recreated=1 (syncUpsert), version 递增 |
| 6 | 1 文件缺失 type，1 文件正常 | errors=1, created=1, 正常文件不受影响 |
| 7 | frontmatter 解析异常 | errors=1, 捕获异常不中断流水线 |

### 5.6 Integration Test

| # | 场景 | 预期 |
|---|------|------|
| 1 | 端到端：POST source → 创建 .md → POST sync → GET knowledge | API 返回已索引条目 |
| 2 | ChangeEventInterceptor 记录 | event_log 有 CREATE 事件 |
| 3 | GET /v1/knowledge/{id} 含 body | body 字段为 Markdown 正文 |
| 4 | KnowledgeArticle 无 POST 端点 | `/v1/knowledge` 不接受 POST（404 或 405） |

---

## 6. Acceptance Criteria

1. `POST /v1/knowledge/sources` 创建知识源，`GET` 可查询
2. `POST /v1/knowledge/sources/{id}/sync` 执行同步，返回 SyncReport（含 created/updated/removed/skipped/errors 计数）
3. 第二次 sync 无变更时 skipped = 总数，created=updated=0
4. `GET /v1/knowledge` 返回已索引的知识条目列表
5. `GET /v1/knowledge/{id}` 返回完整条目（含 body, frontmatter, tags）
6. 缺失 type 的文件产生 sync error，但不阻塞其他文件
7. event_log 记录所有 CREATE/UPDATE 事件
8. 所有单元测试通过（5 个测试类）
9. 集成测试通过

---

## 7. Dependencies

| 依赖 | 来源 | 用途 |
|------|------|------|
| SnakeYAML | Spring Boot starter (transitive) | YAML frontmatter 解析 |
| Hibernate Types (JsonType) | `io.hypersistence:hypersistence-utils-hibernate-63` (已存在) | JSONB 列映射 |
| EntityRepository | 现有 `com.heirloom.repository.EntityRepository` | Repository 基类 |
| EntityResource | 现有 `com.heirloom.web` (待创建或已有模式) | Resource 基类 |
| ChangeEventInterceptor | 现有 `com.heirloom.interceptor` | 自动审计 |
| NoopAuthorizer | 现有 `com.heirloom.auth.NoopAuthorizer` | Phase 0.5a 无认证 |
