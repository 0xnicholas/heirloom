# ADR-032b: Knowledge Base Module — Deep Dive

**关联主文档**: [ADR-032 知识库模块架构](./032-knowledge-base-architecture.md)
**状态**: Proposed
**日期**: 2026-06-22

本文档深化 ADR-032 中五个关键子系统的设计：同步引擎、前端解析器、链接解析器、搜索引擎、API 契约。每个子系统给出具体算法、数据结构、边界条件。

---

## 1. KnowledgeSyncEngine — 核心同步算法

### 1.1 拓扑模型

同步入 Discovery Engine 的拓扑模型——声明式节点树，每节点定义 producer + stages + children：

```java
/**
 * 知识同步的声明式拓扑——每个 KnowledgeSource 类型对应一个拓扑。
 * 与 DiscoveryTopology 共享 Node/Stage 结构，但 producer 和 stage 语义不同。
 */
public record KnowledgeSyncNode(
    String name,
    String producer,                // 生产者函数引用
    List<KnowledgeSyncStage> stages,// 执行阶段序列
    List<String> children,          // 子节点名称（深度优先遍历）
    boolean parallel                // 子节点是否可并行
) {}

public record KnowledgeSyncStage(
    String processor,               // 处理函数名
    String contextKey               // 阶段输出存入上下文的 key
) {}
```

**默认拓扑——文件目录同步**：

```
root
├── scan_filesystem               # 扫描文件系统，产出文件列表
│   ├── stage: walk_directory      → context.filePaths[]
│   ├── stage: compute_hashes      → context.fileHashes{}
│   └── stage: diff_with_db        → context.diffReport{}
│
├── process_new_files (parallel)   # 并行处理新文件 + 变更文件
│   ├── stage: read_file           → context.fileContent
│   ├── stage: extract_frontmatter → context.frontmatter{}
│   ├── stage: extract_body        → context.body
│   ├── stage: resolve_links       → context.references[]
│   └── stage: build_article       → context.article
│
├── detect_stale                   # 检测已删除的文件
│   └── stage: mark_deleted        → context.staleArticles[]
│
└── finalize
    └── stage: build_report        → SyncReport
```

### 1.2 增量同步算法

```
输入: KnowledgeSource source
输出: SyncReport

算法:
  1. 扫描文件系统:
     knownFiles = walkDirectory(source.path)  # 所有 .md 文件（跳过 index.md, log.md）
     
  2. 计算 hash:
     currentHashes = { filePath → SHA256(contents) | filePath ∈ knownFiles }
     
  3. 查询已有索引:
     indexed = SELECT file_path, file_hash FROM knowledge_articles
               WHERE source_fqn = source.fqn AND deleted = false
     indexedMap = MAP(indexed, key=filePath, value=(fileHash, articleId))
     
  4. 计算 diff:
     newFiles     = knownFiles - keys(indexedMap)
     changedFiles = { f | f ∈ knownFiles ∩ keys(indexedMap), currentHashes[f] ≠ indexedMap[f].hash }
     removedFiles = keys(indexedMap) - knownFiles
     unchanged    = knownFiles - newFiles - changedFiles
     
  5. 处理 newFiles + changedFiles（可并行）:
     for each file in (newFiles ∪ changedFiles):
       try:
         content = readFile(file)
         article = parseAndBuildArticle(content, source, file, currentHashes[file])
         if article exists in DB:
           UPDATE knowledge_articles
         else:
           INSERT knowledge_articles
       catch ParseException e:
         article = buildErrorArticle(file, e)
         INSERT or UPDATE with syncStatus = "PARSE_ERROR"
       
  6. 处理 removedFiles:
     for each file in removedFiles:
       article = findArticleByPath(file)
       article.deleted = true; UPDATE
       
  7. 构建报告
```

### 1.3 文件扫描

```java
/**
 * 扫描 knowledge source 指定的目录，返回所有 .md 文件。
 * 默认排除：index.md、log.md、以 . 开头的文件、node_modules/
 */
public class FileScanner {

    private static final Set<String> RESERVED_NAMES = Set.of("index.md", "log.md");
    private static final Pattern IGNORE_PATTERN = Pattern.compile("^\\.|node_modules|target");

    public Map<String, String> scan(KnowledgeSource source) {
        Path root = Path.of(source.getPath());
        if (!Files.isDirectory(root)) {
            throw new SyncException("Path is not a directory: " + source.getPath());
        }

        Map<String, String> fileHash = new HashMap<>();
        try (var stream = Files.walk(root)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .filter(p -> !RESERVED_NAMES.contains(p.getFileName().toString()))
                .filter(p -> IGNORE_PATTERN.matcher(p.toString()).find() == false)
                .forEach(p -> {
                    String relativePath = root.relativize(p).toString();
                    String hash = sha256(p);
                    fileHash.put(relativePath, hash);
                });
        }
        return fileHash;
    }

    private String sha256(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new SyncException("Failed to hash: " + path, e);
        }
    }
}
```

### 1.4 变更检测与 Diff

```java
public record SyncDiff(
    List<String> newFiles,        // 文件系统有，DB 无
    List<String> changedFiles,    // 文件系统有，DB 有但 hash 不同
    List<String> removedFiles,    // DB 有，文件系统无
    List<String> unchangedFiles,  // hash 完全一致，跳过
    int total                      // 扫描到的文件总数
) {
    public boolean hasChanges() {
        return !newFiles.isEmpty() || !changedFiles.isEmpty() || !removedFiles.isEmpty();
    }
}

public SyncDiff diff(
    Map<String, String> currentHashes,      // 文件系统 hash
    Map<String, ArticleRef> indexed         // DB 索引: filePath → (hash, id)
) {
    Set<String> current = currentHashes.keySet();
    Set<String> indexedPaths = indexed.keySet();

    List<String> newFiles = new ArrayList<>();
    List<String> changedFiles = new ArrayList<>();
    List<String> unchangedFiles = new ArrayList<>();

    for (String path : current) {
        String currentHash = currentHashes.get(path);
        ArticleRef existing = indexed.get(path);
        if (existing == null) {
            newFiles.add(path);
        } else if (!currentHash.equals(existing.hash())) {
            changedFiles.add(path);
        } else {
            unchangedFiles.add(path);
        }
    }

    List<String> removedFiles = new ArrayList<>();
    for (String path : indexedPaths) {
        if (!current.contains(path)) {
            removedFiles.add(path);
        }
    }

    return new SyncDiff(newFiles, changedFiles, removedFiles, unchangedFiles, current.size());
}
```

### 1.5 并发模型

```java
/**
 * 同步执行器——控制并发度和超时。
 * 新文件和变更文件可并行处理（每文件独立，无共享可变状态）。
 * 默认并发度 = CPU 核心数 × 2，最大不超过 16。
 */
public class SyncExecutor {
    private final ExecutorService executor;
    private final int maxConcurrency;
    private final Duration fileTimeout = Duration.ofSeconds(30);
    private final Duration syncTimeout = Duration.ofMinutes(10);

    public SyncResult processBatch(List<String> files, FileProcessor processor, int maxConcurrency) {
        List<Future<ArticleResult>> futures = new ArrayList<>();
        for (String file : files) {
            futures.add(executor.submit(() -> {
                try {
                    return processor.process(file);
                } catch (Exception e) {
                    return ArticleResult.error(file, e);
                }
            }));
        }

        SyncResult result = new SyncResult();
        for (int i = 0; i < futures.size(); i++) {
            try {
                ArticleResult ar = futures.get(i).get(fileTimeout.toMillis(), MILLISECONDS);
                result.add(ar);
            } catch (TimeoutException e) {
                result.add(ArticleResult.error(files.get(i), "Timeout after " + fileTimeout));
            } catch (Exception e) {
                result.add(ArticleResult.error(files.get(i), e));
            }
        }
        return result;
    }
}
```

---

## 2. Frontmatter 解析器

### 2.1 解析算法

```
输入: 文件内容（String）
输出: ParseResult { frontmatter: Map, body: String, errors: [] }

算法:
  1. 检查第一行是否为 "---" —— 如果不是，这是普通 Markdown，没有 frontmatter
  2. 寻找第二个 "---"（单独一行）—— 提取中间部分为 YAML 文本
  3. 如果只有开头 "---" 没有结束 "---"，标记为 UNCLOSED_FRONTMATTER
  4. YAML 解析（使用 SnakeYAML）:
     a. 如果解析成功 → frontmatter = 解析结果
     b. 如果解析失败 → frontmatter = {}, errors += PARSE_ERROR
  5. 提取 body = 第二个 "---" 之后的所有内容
  6. 校验必填字段:
     if frontmatter.type is null or empty:
       errors += MISSING_TYPE
       return as parse error (不创建 KnowledgeArticle，但记录文件存在)
  7. 规范化已知字段:
     - type: 去除首尾空白，保留原始大小写
     - title: 如果缺失，从文件名推导（文件名去 .md → 替换 - 和 _ 为空格 → Title Case）
     - description: 截断到 1024 字符
     - tags: 如果缺失 → []，如果是字符串 → [字符串]
     - resource: 保留为字符串
     - timestamp: 解析为 ISO 8601
```

### 2.2 边界情况处理

| 边界情况 | 行为 |
|---------|------|
| 文件为空 | `MISSING_TYPE` error，不索引 |
| frontmatter 只有 `---`（空 frontmatter） | `MISSING_TYPE` error，不索引 |
| type 值有不可见字符（如 `"Playbook\r"`）| 去除首尾空白，包括控制字符 |
| tags 是字符串而非数组（`tags: sales`） | 自动包装为 `["sales"]` |
| timestamp 格式非法 | 忽略，记录 warning，不影响索引 |
| frontmatter 中有非 ASCII key | 保留原样——这是合法的 YAML |
| body 超过 10MB | 截断并记录 TRUNCATED_BODY warning |
| frontmatter 中有重复 key | YAML 后值覆盖前值 |

### 2.3 自定义扩展字段

```java
/**
 * frontmatter 中已知字段以外的所有 key-value 全部保留在 JSONB 中。
 * 消费者可通过 API 查询这些自定义字段：
 * 
 *   GET /v1/knowledge/search?frontmatter.owner=data-team
 *   GET /v1/knowledge/search?frontmatter.sla=30m
 */
public Map<String, Object> normalizeFrontmatter(Map<String, Object> raw) {
    Map<String, Object> normalized = new HashMap<>();
    
    // 已知字段：提取并类型化
    normalized.put("type",       stringOrNull(raw.remove("type")));
    normalized.put("title",      stringOrNull(raw.remove("title")));
    normalized.put("description",stringOrNull(raw.remove("description")));
    normalized.put("tags",       normalizeTags(raw.remove("tags")));
    normalized.put("resource",   stringOrNull(raw.remove("resource")));
    normalized.put("timestamp",  stringOrNull(raw.remove("timestamp")));
    
    // 所有剩余字段作为扩展字段保留
    for (var entry : raw.entrySet()) {
        normalized.put("x_" + entry.getKey(), entry.getValue());
    }
    
    return normalized;
}
```

### 2.4 文件名到 title 的推导

```java
/**
 * 如果 frontmatter 没有 title，从文件名推导。
 * "customer-segmentation-guide.md" → "Customer Segmentation Guide"
 * "REVENUE_LTV.md"               → "Revenue LTV"
 */
public String deriveTitle(String filePath) {
    String filename = Path.of(filePath).getFileName().toString();
    // 去 .md 后缀
    String name = filename.replaceAll("\\.md$", "");
    // 分割：-、_、空格
    String[] words = name.split("[-_\\s]+");
    // 每个词首字母大写（保留已有大写，如 LTV）
    return Arrays.stream(words)
        .map(w -> w.substring(0, 1).toUpperCase() + w.substring(1))
        .collect(Collectors.joining(" "));
}
```

---

## 3. Markdown 链接 → EntityReference 解析器

### 3.1 链接形式

OKF 支持两种链接形式，KnowledgeSyncEngine 都需要解析：

```
绝对链接（bundle-relative）：[customers](/tables/customers.md)
相对链接：                    [neighboring](./other.md)
外部链接：                    [BigQuery](https://console.cloud.google.com/...)
裸 FQN 引用：                 [metadata_tables.sales.orders](@metadata_tables.sales.orders)  ← Heirloom 扩展
```

### 3.2 解析算法

```java
/**
 * 从 Markdown body 中提取所有链接，尝试解析为 Heirloom EntityReference。
 * 
 * 解析策略：
 *   1. 提取所有 [text](url) 格式的链接
 *   2. 对每个链接：
 *      a. 如果是外部 URL（http:// 或 https://）→ 归入 citations
 *      b. 如果是 @FQN 格式 → 用 FQN 在 EntityRegistry 中查找
 *      c. 如果是 .md 路径 → 尝试映射为 KnowledgeArticle FQN
 *      d. 如果是其他内部路径 → 标记为未解析引用（不报错）
 *   3. 如果链接目标在 EntityRegistry 中存在 → EntityReference
 *   4. 如果链接目标不存在 → 记录为 UnresolvedReference（Phase 4 可提供 UI 提示）
 */
public record LinkParseResult(
    List<EntityReference> resolvedReferences,
    List<UnresolvedReference> unresolvedReferences,
    List<ExternalCitation> citations
) {}

public LinkParseResult parseLinks(String body, String sourceFqn) {
    Pattern linkPattern = Pattern.compile("\\[([^\\]]*)\\]\\(([^\\)]*)\\)");
    Matcher matcher = linkPattern.matcher(body);
    
    List<EntityReference> resolved = new ArrayList<>();
    List<UnresolvedReference> unresolved = new ArrayList<>();
    List<ExternalCitation> citations = new ArrayList<>();
    
    while (matcher.find()) {
        String linkText = matcher.group(1);
        String linkUrl = matcher.group(2);
        
        if (linkUrl.startsWith("http://") || linkUrl.startsWith("https://")) {
            citations.add(new ExternalCitation(linkUrl, linkText, null));
        } else if (linkUrl.startsWith("@")) {
            // Heirloom 扩展：@FQN 直接引用
            String fqn = linkUrl.substring(1);
            tryResolveFQN(fqn, linkText, resolved, unresolved);
        } else if (linkUrl.endsWith(".md")) {
            // OKF 标准：bundle-relative 路径 → 映射为 KnowledgeArticle FQN
            String mappedFqn = mapFilePathToKnowledgeFQN(linkUrl, sourceFqn);
            tryResolveFQN(mappedFqn, linkText, resolved, unresolved);
        } else {
            // 其他内部路径（不太可能对应 Heirloom 实体）→ 未解析引用
            unresolved.add(new UnresolvedReference(linkUrl, linkText, "unknown path scheme"));
        }
    }
    
    return new LinkParseResult(resolved, unresolved, citations);
}

private void tryResolveFQN(String fqn, String label,
                            List<EntityReference> resolved,
                            List<UnresolvedReference> unresolved) {
    try {
        // 尝试在 EntityRegistry 中找到对应实体
        for (String entityType : EntityRegistry.getAllEntityTypes()) {
            Class<?> clazz = EntityRegistry.getEntityClass(entityType);
            // 检查是否有 findByFQN 方法——如果能找到，说明链接有效
            EntityRepository<?> repo = EntityRegistry.getRepository(entityType);
            if (repo != null && repo.findByFQN(fqn).isPresent()) {
                resolved.add(new EntityReference(fqn, entityType, label, linkText));
                return;
            }
        }
        unresolved.add(new UnresolvedReference(fqn, label, "target FQN not found in EntityRegistry"));
    } catch (Exception e) {
        unresolved.add(new UnresolvedReference(fqn, label, e.getMessage()));
    }
}

private String mapFilePathToKnowledgeFQN(String linkPath, String sourceFqn) {
    // "tables/orders.md" → "knowledge.{source}.tables.orders"
    // "../shared/glossary.md" → "knowledge.{source}.shared.glossary"（先 resolve .. 再转换）
    String normalized = Path.of(linkPath).normalize().toString()
        .replaceAll("\\.md$", "")
        .replace('/', '.');
    // sourceFqn 例如 "knowledgeSource.primary"
    String sourceName = sourceFqn.contains(".") ? sourceFqn.substring(sourceFqn.indexOf('.') + 1) : sourceFqn;
    return "knowledge." + sourceName + "." + normalized;
}
```

### 3.3 引用一致性边界

| 场景 | 行为 |
|------|------|
| 链接目标实体被删除 | 引用变为 stale——下次同步时检测到 `tryResolveFQN` 失败 → 移入 unresolved |
| 链接目标实体被重命名（FQN 变更） | 同上——FQN 不再匹配 |
| 新同步时链接被作者移除 |引用列表自动更新（整篇文章重新解析） |
| 作者添加了新的链接 | 下次同步时自动解析 |

---

## 4. 搜索架构

### 4.1 Phase 1：PostgreSQL 全文检索

```sql
-- 搜索查询模板
SELECT id, fqn, type, title, description,
       ts_rank(search_vector, query) AS rank
FROM knowledge_articles,
     to_tsquery('english', :query) AS query
WHERE search_vector @@ query
  AND deleted = false
  AND (:type IS NULL OR type = :type)
  AND (:domain IS NULL OR domain = :domain)
  AND (:tag IS NULL OR tags @> (:tag)::jsonb)
ORDER BY rank DESC
LIMIT :limit OFFSET :offset;
```

**search_vector** 是一个 `GENERATED ALWAYS` 列，组合 title + description + body：

```sql
ALTER TABLE knowledge_articles 
  ADD COLUMN search_vector tsvector 
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(description, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(body, '')), 'C')
  ) STORED;

CREATE INDEX idx_ka_search ON knowledge_articles USING GIN (search_vector);
```

**权重设计**：
- `A` (title)：最高权重——标题匹配最相关
- `B` (description)：中等权重
- `C` (body)：最低权重——正文匹配相关性较弱

### 4.2 查询清理与转换

```java
/**
 * 将用户查询转换为 PostgreSQL tsquery 安全格式。
 * 处理特殊字符、前缀匹配、短语匹配。
 */
public String toTsQuery(String rawQuery) {
    // 1. 去除特殊字符，保留字母数字和空格
    String cleaned = rawQuery.replaceAll("[^\\w\\s]", " ");
    
    // 2. 分词
    String[] words = cleaned.trim().split("\\s+");
    if (words.length == 0) {
        throw new IllegalArgumentException("Empty search query");
    }
    
    // 3. 每个词添加前缀匹配（:*）
    // "customer segmentation" → "customer:* & segmentation:*"
    String terms = Arrays.stream(words)
        .map(w -> w + ":*")
        .collect(Collectors.joining(" & "));
    
    return terms;
}
```

### 4.3 Phase 3：混合搜索

```
算法: HybridSearch(query, limit)
输入:
  query: 用户查询字符串
  limit: 返回结果数
输出:
  List<SearchResult> 混合排序后的结果

步骤:
  1. FTS 搜索:
     fts_results = FTS_SEARCH(to_tsquery(query), limit × 2)    # 取 2 倍候选集
    
  2. 向量搜索（如果 embedding 可用）:
     query_embedding = embed(query)                              # 调用 embedding API
     vector_results = SELECT id, 1 - (embedding <=> query_embedding) AS similarity
                      FROM knowledge_articles
                      WHERE embedding IS NOT NULL AND deleted = false
                      ORDER BY embedding <=> query_embedding
                      LIMIT limit × 2
    
  3. 合并与重排序:
     combined = merge(fts_results, vector_results)
     sorted = rankByReciprocalRankFusion(combined, k=60)
     return sorted[:limit]
```

**倒数排名融合 (RRF)**：

```java
public List<SearchResult> reciprocalRankFusion(
    List<SearchResult> ftsResults,
    List<SearchResult> vectorResults,
    int k
) {
    Map<Long, Double> scores = new HashMap<>();
    
    // FTS 排名贡献
    for (int i = 0; i < ftsResults.size(); i++) {
        long id = ftsResults.get(i).id();
        scores.merge(id, 1.0 / (k + i + 1), Double::sum);
    }
    
    // 向量排名贡献
    for (int i = 0; i < vectorResults.size(); i++) {
        long id = vectorResults.get(i).id();
        scores.merge(id, 1.0 / (k + i + 1), Double::sum);
    }
    
    // 按融合分数降序排列
    return scores.entrySet().stream()
        .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
        .map(e -> buildResult(e.getKey(), e.getValue()))
        .toList();
}
```

### 4.4 反向引用搜索

```sql
-- 找出所有引用实体 X 的知识条目
SELECT * FROM knowledge_articles
WHERE references_jsonb @> '[{"fqn": ":targetFqn"}]'
  AND deleted = false
ORDER BY updated_at DESC;
```

利用 GIN 索引的 `@>` 包含操作符，不需要全表扫描。

---

## 5. KnowledgeArticleRepository

### 5.1 核心实现

```java
@Repository
public class KnowledgeArticleRepository extends EntityRepository<KnowledgeArticle> {

    private final KnowledgeArticleJpaRepository jpa;

    public KnowledgeArticleRepository(KnowledgeArticleJpaRepository jpa) {
        super(EntityRegistry.KNOWLEDGE_ARTICLE, KnowledgeArticle.class, jpa);
        this.jpa = jpa;
    }

    @PostConstruct
    void init() {
        EntityRegistry.register(
            EntityRegistry.KNOWLEDGE_ARTICLE,
            KnowledgeArticle.class,
            this,
            null,                          // service — Phase 0.5 不需要
            "knowledge.{source}.{path}",   // FQN 模板
            "/v1/knowledge"
        );
    }

    @Override
    protected void setFullyQualifiedName(KnowledgeArticle article) {
        // knowledgeSource.primary + knowledge/tables/orders.md
        // → knowledge.primary.tables.orders
        String sourceName = article.getSourceFqn();
        if (sourceName != null && sourceName.contains(".")) {
            sourceName = sourceName.substring(sourceName.indexOf('.') + 1);
        } else {
            sourceName = "default";
        }
        String path = article.getFilePath()
            .replaceAll("\\.md$", "")
            .replace('/', '.');
        article.setFullyQualifiedName("knowledge." + sourceName + "." + path);
    }

    @Override
    protected void prepareInternal(KnowledgeArticle article, boolean isUpdate) {
        // 不需要特殊准备
    }

    // === 知识库特有方法 ===

    /**
     * 按文件路径和来源查找——同步时使用。
     */
    public Optional<KnowledgeArticle> findByFilePath(String sourceFqn, String filePath) {
        return jpa.findBySourceFqnAndFilePath(sourceFqn, filePath);
    }

    /**
     * 获取某个 KnowledgeSource 下所有已索引文件路径及其 hash。
     * 用于 diff 计算。
     */
    public Map<String, String> getIndexedFileHashes(String sourceFqn) {
        return jpa.findBySourceFqnAndDeletedFalse(sourceFqn)
            .stream()
            .collect(Collectors.toMap(
                KnowledgeArticle::getFilePath,
                KnowledgeArticle::getFileHash
            ));
    }

    /**
     * 搜索——全文检索
     */
    public EntityList<KnowledgeArticle> search(String query, String type, 
                                                String domain, List<String> tags,
                                                String refFqn, int limit, int offset) {
        // 委托给 jpa 的 @Query 方法
        var results = jpa.search(query, type, domain, tags, refFqn, limit, offset);
        return EntityList.of(results, results.size());
    }
}
```

### 5.2 JPA 接口

```java
public interface KnowledgeArticleJpaRepository extends JpaRepository<KnowledgeArticle, Long> {

    Optional<KnowledgeArticle> findByFullyQualifiedName(String fqn);

    Optional<KnowledgeArticle> findBySourceFqnAndFilePath(String sourceFqn, String filePath);

    List<KnowledgeArticle> findBySourceFqnAndDeletedFalse(String sourceFqn);

    @Query(value = """
        SELECT * FROM knowledge_articles
        WHERE search_vector @@ to_tsquery('english', :query)
          AND deleted = false
          AND (:type IS NULL OR type = :type)
          AND (:domain IS NULL OR domain = :domain)
          AND (:tagFilter IS NULL OR tags @> CAST(:tagFilter AS jsonb))
          AND (:refFqn IS NULL OR references_jsonb @> CAST(:refFilter AS jsonb))
        ORDER BY ts_rank(search_vector, to_tsquery('english', :query)) DESC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<KnowledgeArticle> search(@Param("query") String query,
                                   @Param("type") String type,
                                   @Param("domain") String domain,
                                   @Param("tagFilter") String tagFilter,
                                   @Param("refFqn") String refFqn,
                                   @Param("refFilter") String refFilter,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);
}
```

### 5.3 同步 Upsert 逻辑

```java
/**
 * 同步引擎使用——不通过 create()/update() 模板方法，
 * 而是直接操作 JPA 以实现 upsert 语义。
 * 
 * 原因：EntityRepository.create() 总是生成新 ID。
 * 同步场景下，同一路径的文件应更新已有记录。
 */
@Transactional
public KnowledgeArticle syncUpsert(KnowledgeArticle article) {
    Optional<KnowledgeArticle> existing = 
        jpa.findBySourceFqnAndFilePath(article.getSourceFqn(), article.getFilePath());
    
    if (existing.isPresent()) {
        KnowledgeArticle toUpdate = existing.get();
        copySyncFields(article, toUpdate);
        toUpdate.setVersion(toUpdate.getVersion() + 1);
        return jpa.save(toUpdate);
    } else {
        setFullyQualifiedName(article);
        article.setCreatedAt(Instant.now());
        article.setUpdatedAt(Instant.now());
        return jpa.save(article);
    }
}

private void copySyncFields(KnowledgeArticle source, KnowledgeArticle target) {
    target.setFileHash(source.getFileHash());
    target.setType(source.getType());
    target.setTitle(source.getTitle());
    target.setDescription(source.getDescription());
    target.setBody(source.getBody());
    target.setFrontmatterRaw(source.getFrontmatterRaw());
    target.setFrontmatter(source.getFrontmatter());
    target.setTags(source.getTags());
    target.setReferences(source.getReferences());
    target.setCitations(source.getCitations());
    target.setDomain(source.getDomain());
    target.setAuthor(source.getAuthor());
    target.setSyncStatus(source.getSyncStatus());
    target.setSyncError(source.getSyncError());
    target.setLastSyncedAt(Instant.now());
    target.setUpdatedAt(Instant.now());
}
```

---

## 6. API 契约

### 6.1 KnowledgeSource

```
POST /v1/knowledge/sources
Request:
  {
    "name": "primary",
    "sourceType": "directory",
    "path": "/data/knowledge/",
    "schedule": "manual",
    "description": "Main knowledge repository",
    "owner": "data-team"
  }

Response 201:
  {
    "id": 1,
    "name": "primary",
    "fullyQualifiedName": "knowledgeSource.primary",
    "sourceType": "directory",
    "path": "/data/knowledge/",
    "schedule": "manual",
    "status": "ACTIVE",
    "description": "Main knowledge repository",
    "owner": "data-team",
    "version": 1,
    "createdAt": "2026-06-22T10:00:00Z",
    "updatedAt": "2026-06-22T10:00:00Z"
  }

GET /v1/knowledge/sources?fields=name,sourceType,path,status,lastSyncResult
Response 200:
  {
    "data": [
      {
        "id": 1,
        "name": "primary",
        "fullyQualifiedName": "knowledgeSource.primary",
        "sourceType": "directory",
        "path": "/data/knowledge/",
        "status": "ACTIVE",
        "lastSyncResult": {
          "syncedAt": "2026-06-22T09:55:00Z",
          "created": 3,
          "updated": 1,
          "removed": 0,
          "skipped": 12,
          "errors": []
        }
      }
    ],
    "total": 1
  }

POST /v1/knowledge/sources/{id}/sync
Request: (empty body)
Response 202:
  {
    "status": "IN_PROGRESS",
    "sourceId": 1
  }

GET /v1/knowledge/sources/{id}/sync/status
Response 200:
  {
    "status": "COMPLETED",
    "report": {
      "syncedAt": "2026-06-22T10:01:00Z",
      "durationMs": 3450,
      "totalFiles": 127,
      "created": 0,
      "updated": 1,
      "removed": 0,
      "skipped": 126,
      "errors": [
        {
          "filePath": "drafts/incomplete.md",
          "error": "Missing type in frontmatter"
        }
      ]
    }
  }
```

### 6.2 KnowledgeArticle（只读查询）

```
GET /v1/knowledge?type=Playbook&domain=engineering&limit=20
Response 200:
  {
    "data": [
      {
        "id": 5,
        "fullyQualifiedName": "knowledge.primary.playbooks.incident-response",
        "type": "Playbook",
        "title": "Incident Response — Freshness Alert",
        "description": "Steps to triage a freshness alert on the orders pipeline.",
        "filePath": "playbooks/incident-response.md",
        "sourceFqn": "knowledgeSource.primary",
        "tags": ["oncall", "incident"],
        "domain": "engineering",
        "author": "data-team",
        "status": "OK",
        "lastSyncedAt": "2026-06-22T09:55:00Z",
        "version": 3,
        "updatedAt": "2026-06-22T09:55:00Z"
      }
    ],
    "total": 1
  }

GET /v1/knowledge/{id}?fields=body,frontmatter,references
Response 200:
  {
    "id": 5,
    "fullyQualifiedName": "knowledge.primary.playbooks.incident-response",
    "type": "Playbook",
    "title": "Incident Response — Freshness Alert",
    "description": "Steps to triage a freshness alert on the orders pipeline.",
    "body": "# Trigger\n\nThe freshness alert fires when `orders` falls more than 30 minutes...",
    "frontmatter": {
      "type": "Playbook",
      "title": "Incident Response — Freshness Alert",
      "description": "Steps to triage a freshness alert on the orders pipeline.",
      "tags": ["oncall", "incident"],
      "x_owner": "data-team@company.com"
    },
    "references": [
      {
        "fqn": "metadata_tables.acme.sales.orders",
        "entityType": "table",
        "label": "主监控对象"
      }
    ],
    "citations": [
      {
        "url": "https://example.com/dash",
        "title": "Ingestion job dashboard"
      }
    ],
    "sourceFqn": "knowledgeSource.primary",
    "version": 3,
    "createdAt": "2026-06-15T08:00:00Z",
    "updatedAt": "2026-06-22T09:55:00Z"
  }
```

### 6.3 搜索

```
GET /v1/knowledge/search?q=customer+segmentation&type=Playbook&tags=oncall&ref=metadata_tables.sales.orders&limit=10

Response 200:
  {
    "data": [
      {
        "id": 5,
        "fullyQualifiedName": "knowledge.primary.playbooks.incident-response",
        "type": "Playbook",
        "title": "Incident Response — Freshness Alert",
        "description": "...",
        "_rank": 0.92,
        "_matchHighlights": ["<b>customer</b> <b>segmentation</b> pipeline monitors..."]
      }
    ],
    "total": 1,
    "query": "customer segmentation",
    "filters": {
      "type": "Playbook",
      "tags": ["oncall"],
      "ref": "metadata_tables.sales.orders"
    }
  }
```

### 6.4 错误响应

```json
// 尝试创建 KnowledgeArticle（不支持）
POST /v1/knowledge → 405 Method Not Allowed
{
  "title": "Knowledge articles are managed by sync engine",
  "detail": "Knowledge articles originate from file system. To create or edit knowledge, modify the .md file and trigger a sync.",
  "type": "https://heirloom.dev/errors/read-only-entity"
}

// 同步失败——文件有误
{
  "title": "Sync completed with errors",
  "detail": "3 files failed to parse",
  "errors": [
    {
      "filePath": "playbooks/broken.md",
      "error": "Unclosed frontmatter block",
      "lineNumber": 1
    },
    {
      "filePath": "metrics/no-type.md",
      "error": "Missing required field 'type' in frontmatter"
    }
  ]
}

// 知识源路径不可达
{
  "title": "Knowledge source path not accessible",
  "detail": "Path '/nonexistent/knowledge/' is not a directory or does not exist",
  "type": "https://heirloom.dev/errors/path-inaccessible"
}
```

---

## 7. 同步报告模型

```java
public class SyncReport {
    private String sourceFqn;
    private String status;                    // "IN_PROGRESS" | "COMPLETED" | "FAILED"
    private Instant startedAt;
    private Instant completedAt;
    private long durationMs;
    
    // 统计
    private int totalFiles;
    private int created;
    private int updated;
    private int removed;
    private int skipped;                     // hash 未变，跳过
    private int errors;                      // 解析失败
    
    // 详情
    private List<SyncError> errorDetails;
    
    public record SyncError(
        String filePath,
        String error,
        Integer lineNumber,
        String errorType                   // "MISSING_TYPE" | "PARSE_ERROR" | "UNCLOSED_FRONTMATTER" | "FILE_TOO_LARGE"
    ) {}
    
    // 便捷方法
    public boolean hasErrors() { return errors > 0; }
    public boolean hasChanges() { return created + updated + removed > 0; }
}
```

---

## 8. 异常层次

```java
// 同步层异常
public class SyncException extends RuntimeException { ... }

// 解析层异常
public class FrontmatterParseException extends SyncException {
    private final String filePath;
    private final Integer lineNumber;
    private final String errorType;
}

// 搜索层异常
public class KnowledgeSearchException extends RuntimeException { ... }

// 知识源配置异常
public class KnowledgeSourceException extends RuntimeException { ... }
```

这些异常由现有的 `GlobalExceptionHandler` 统一处理（`@RestControllerAdvice`），遵循 `ProblemDetail` RFC 格式。

---

## 9. 测试策略

### 9.1 单元测试

| 组件 | 测试覆盖 |
|------|---------|
| `FileScanner` | 空目录、混合文件（.md + .txt）、index.md 排除、嵌套目录、符号链接 |
| `FrontmatterParser` | 合法 frontmatter、无 frontmatter、空 frontmatter、非法 YAML、缺失 type、tags 是字符串、timestamp 格式错误、超长 description、非 ASCII key |
| `LinkResolver` | @FQN 引用、.md 相对路径、外部 URL、目标不存在、路径含 `..` |
| `SyncDiff` | 全部新文件、全部未变、混合、全部删除、空文件集 |
| `KnowledgeArticleRepository` | FQN 生成、syncUpsert 新建 vs 更新、findByFilePath |

### 9.2 集成测试

```java
@SpringBootTest
class KnowledgeSyncIntegrationTest {

    @TempDir Path knowledgeDir;
    
    @Test
    void shouldSyncNewFilesAndDetectChanges() {
        // 1. 注册 KnowledgeSource
        KnowledgeSource source = createSource(knowledgeDir);
        
        // 2. 创建测试文件
        writeFile(knowledgeDir, "tables/orders.md", """
            ---
            type: BigQuery Table
            title: Orders
            ---
            # Schema
            ...
            """);
        
        // 3. 触发同步
        SyncReport report = syncEngine.sync(source.getFullyQualifiedName());
        assertThat(report.getCreated()).isEqualTo(1);
        
        // 4. 验证索引
        KnowledgeArticle article = articleRepo
            .findByFilePath(source.getFqn(), "tables/orders.md")
            .orElseThrow();
        assertThat(article.getTitle()).isEqualTo("Orders");
        assertThat(article.getType()).isEqualTo("BigQuery Table");
        
        // 5. 修改文件
        writeFile(knowledgeDir, "tables/orders.md", """
            ---
            type: BigQuery Table
            title: Orders (Updated)
            ---
            # Schema
            ...
            """);
        
        // 6. 再次同步
        SyncReport report2 = syncEngine.sync(source.getFullyQualifiedName());
        assertThat(report2.getUpdated()).isEqualTo(1);
        assertThat(report2.getSkipped()).isEqualTo(0);
        
        // 7. 验证更新
        KnowledgeArticle updated = articleRepo
            .findByFilePath(source.getFqn(), "tables/orders.md")
            .orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Orders (Updated)");
        assertThat(updated.getVersion()).isEqualTo(2);
    }
    
    @Test
    void shouldDetectRemovedFiles() {
        // ... 删除文件 × 同步 × 验证 deleted = true
    }
    
    @Test
    void shouldHandleMissingTypeGracefully() {
        writeFile(knowledgeDir, "broken.md", "# No frontmatter");
        SyncReport report = syncEngine.sync(source.getFqn());
        assertThat(report.getErrors()).isEqualTo(1);
        assertThat(report.getErrorDetails().get(0).getErrorType())
            .isEqualTo("MISSING_TYPE");
    }
}
```

---

## 10. OKF 自足性增强：index.md 与 log.md 自动生成

### 10.1 设计动机

OKF 的核心理念是 bundle 完全自描述——`git clone` 后不需额外工具即可浏览全貌。当前设计依赖 Heirloom 数据库提供搜索和聚合能力，但文件目录本身缺少 OKF §6 和 §7 定义的 `index.md`（目录清单）和 `log.md`（变更历史）。

自动生成这两类文件，使文件目录在脱离 Heirloom 时仍然可用。

### 10.2 index.md 自动生成器

在每次 `KnowledgeSyncEngine.sync()` 完成后执行，为每个目录生成/更新 `index.md`。

**算法**：

```java
public class IndexGenerator {

    private static final String AUTO_START = "<!-- HEIRLOOM_AUTO_START: index -->";
    private static final String AUTO_END   = "<!-- HEIRLOOM_AUTO_END: index -->";

    /**
     * 为 KnowledgeSource 的整个文件树生成 index.md。
     * 已存在的 index.md 中，HEIRLOOM_AUTO 标记外的内容原样保留。
     */
    public IndexReport generate(KnowledgeSource source) {
        Path root = Path.of(source.getPath());
        IndexReport report = new IndexReport();

        // 遍历所有目录（广度优先）
        try (var dirs = Files.walk(root).filter(Files::isDirectory)) {
            dirs.forEach(dir -> {
                try {
                    generateIndexForDirectory(root, dir);
                    report.incrementGenerated();
                } catch (Exception e) {
                    report.addError(dir.toString(), e.getMessage());
                }
            });
        }
        return report;
    }

    private void generateIndexForDirectory(Path root, Path dir) throws IOException {
        // 1. 收集该目录下所有概念文件
        List<KnowledgeArticle> articles = getArticlesInDirectory(sourceFqn, relativePath(root, dir));

        // 2. 按 type 分组
        Map<String, List<KnowledgeArticle>> grouped = articles.stream()
            .collect(Collectors.groupingBy(KnowledgeArticle::getType));

        // 3. 生成 index 内容
        StringBuilder sb = new StringBuilder();
        sb.append(AUTO_START).append("\n");
        
        for (var entry : grouped.entrySet()) {
            sb.append("# ").append(entry.getKey()).append("s\n\n");
            for (KnowledgeArticle a : entry.getValue()) {
                String filename = Path.of(a.getFilePath()).getFileName().toString();
                String desc = a.getDescription() != null ? a.getDescription() : "";
                sb.append("* [").append(a.getTitle()).append("](")
                  .append(filename).append(") - ").append(desc).append("\n");
            }
            sb.append("\n");
        }
        
        sb.append(AUTO_END).append("\n");

        // 4. 写入文件（如果已有 index.md，替换 AUTO 区块，保留其他内容）
        Path indexPath = dir.resolve("index.md");
        if (Files.exists(indexPath)) {
            String existing = Files.readString(indexPath);
            String updated = replaceAutoBlock(existing, sb.toString());
            if (!updated.equals(existing)) {
                Files.writeString(indexPath, updated);
            }
        } else {
            Files.writeString(indexPath, sb.toString());
        }
    }

    private String replaceAutoBlock(String existing, String newBlock) {
        int start = existing.indexOf(AUTO_START);
        int end = existing.indexOf(AUTO_END);
        
        if (start >= 0 && end > start) {
            // 替换已有的 AUTO 区块
            return existing.substring(0, start) + newBlock + existing.substring(end + AUTO_END.length());
        } else {
            // 没有 AUTO 区块——追加
            return existing.trim() + "\n\n" + newBlock;
        }
    }
}
```

**生成效果**——`knowledge/tables/index.md`：

```markdown
<!-- HEIRLOOM_AUTO_START: index -->
# BigQuery Tables
* [Orders](orders.md) - One row per completed customer order
* [Customers](customers.md) - Customer master data

# Metrics
* [Revenue LTV](revenue-ltv.md) - Cumulative revenue per customer over 90 days
<!-- HEIRLOOM_AUTO_END: index -->

## 人工添加的说明
此处的内容不会被自动生成覆盖。
```

### 10.3 log.md 变更日志生成器

在每次同步完成后，在根目录的 `log.md` 追加变更摘要。

**算法**：

```java
public class LogGenerator {

    /**
     * 根据 SyncDiff 结果，生成 log.md 条目。
     * 格式遵循 OKF §7：按日期分组，最近在前，每条目以粗体操作类型开头。
     */
    public void appendLogEntries(KnowledgeSource source, SyncDiff diff, SyncReport report) {
        Path logPath = Path.of(source.getPath()).resolve("log.md");
        
        String dateHeader = "## " + LocalDate.now().toString();
        StringBuilder newEntries = new StringBuilder();

        // 新建
        for (String file : diff.newFiles()) {
            String title = getTitleForFile(file);
            String type = getTypeForFile(file);
            newEntries.append("* **Create**: ")
                .append(type).append(" ").append(title)
                .append(" (auto-generated by discovery)\n");
        }

        // 更新
        for (String file : diff.changedFiles()) {
            String title = getTitleForFile(file);
            newEntries.append("* **Update**: ").append(title)
                .append(" — file content changed\n");
        }

        // 删除
        for (String file : diff.removedFiles()) {
            String title = getTitleForFile(file);
            newEntries.append("* **Deprecation**: ").append(title)
                .append(" — file removed\n");
        }

        // 错误
        for (var error : report.getErrorDetails()) {
            newEntries.append("* **Error**: ").append(error.getFilePath())
                .append(" — ").append(error.getError()).append("\n");
        }

        if (newEntries.isEmpty()) {
            newEntries.append("* **Check**: No changes detected\n");
        }

        // 读取已有 log.md，找到今天的日期头或插入新日期头
        String existing = Files.exists(logPath) ? Files.readString(logPath) : "# Update Log\n\n";
        String updated;
        
        if (existing.contains(dateHeader)) {
            // 在同一天的头下面插入新条目（在下一个 ## 之前）
            int insertPoint = existing.indexOf(dateHeader) + dateHeader.length() + 1;
            int nextHeader = existing.indexOf("\n## ", insertPoint);
            if (nextHeader > 0) {
                updated = existing.substring(0, insertPoint) + newEntries + existing.substring(nextHeader);
            } else {
                updated = existing.substring(0, insertPoint) + newEntries + existing.substring(insertPoint);
            }
        } else {
            // 新日期头——插入在文件头之后
            int insertPoint = existing.indexOf("\n") + 1;
            updated = existing.substring(0, insertPoint) + dateHeader + "\n" + newEntries + "\n" + existing.substring(insertPoint);
        }

        Files.writeString(logPath, updated);
    }
}
```

**生成效果**——`knowledge/log.md`：

```markdown
# Update Log

## 2026-06-22
* **Create**: BigQuery Table [Orders](/tables/orders.md) (auto-generated by discovery)
* **Create**: BigQuery Table [Customers](/tables/customers.md) (auto-generated by discovery)
* **Update**: [Incident Response Playbook](/playbooks/incident-response.md) — file content changed
* **Deprecation**: [Legacy ETL](/tables/legacy-etl.md) — file removed

## 2026-06-21
* **Init**: Knowledge source registered
* **Create**: [Customer Segmentation Guide](/playbooks/segmentation.md)
```

### 10.4 生成触发时机

```java
/**
 * SyncEngine 完成后的后处理链：
 *   1. index.md 生成（每目录）
 *   2. log.md 生成（根目录）
 *   3. 索引生成的文件自身（index.md 和 log.md 的变更会被下一次 sync 检测到，形成反馈循环）
 * 
 * 注意：index.md 和 log.md 本身不在 KnowledgeSyncEngine 中被索引——它们是 OKF 保留文件名。
 * 但它们的变更会触发 git commit，进而在下一次 sync 时产生新的 log 条目。
 * 为避免递归爆炸：log.md 的变更不会触发新的 log 条目（除非有新的人为变更）。
 */
@Component
public class SyncPostProcessor {

    private final IndexGenerator indexGenerator;
    private final LogGenerator logGenerator;

    @EventListener
    public void onSyncCompleted(SyncCompletedEvent event) {
        // 1. 生成 index.md
        IndexReport indexReport = indexGenerator.generate(event.getSource());
        
        // 2. 仅当有实际变更时才追加 log.md（避免无限循环）
        if (event.getDiff().hasChanges()) {
            logGenerator.appendLogEntries(event.getSource(), event.getDiff(), event.getReport());
        }
    }
}
```

### 10.5 自足性检查

基于以上实现，`git clone` 一个 knowledge bundle 后，用户可以：

| 操作 | 纯文件系统 | 效率 |
|------|-----------|------|
| 浏览知识体系 | 逐层打开 `index.md` | ✅ |
| 了解最近变更 | 阅读根 `log.md` | ✅ |
| 理解某个资产 | `cat tables/orders.md` → 看到完整 Markdown + frontmatter | ✅ |
| 全文搜索 | `grep -r "keyword"` | ⚠️ 慢但可用 |
| 反向引用 | ❌ 需 Heirloom 数据库 | — |
| 语义搜索 | ❌ 需 Heirloom pgvector | — |

文件目录在基础场景（浏览、理解、变更追踪）下完全自足。高级场景（搜索、引用图、语义查询）需要 Heirloom 索引层。两者分工明确，互补而非替代。

---

## 参考

- ADR-032: Knowledge Base Module — Architecture Design
- ADR-019: 两阶段发现——提取→推断
- ADR-022: Discovery Topology——声明式遍历树
- ADR-024: Metadata Entity Models — JPA + JSONB
- OKF v0.1: https://okf.md/spec/
