# ADR-033: Knowledge Conversion Pipeline — 三向转换架构

**关联主文档**: [ADR-032 知识库模块架构](./032-knowledge-base-architecture.md)、[ADR-032b 深度设计](./032b-knowledge-base-deep-dive.md)
**状态**: Proposed
**日期**: 2026-06-22

## 上下文

当前知识库架构中，知识文件是真相源，KnowledgeSyncEngine 将文件索引到数据库。但三个实际需求尚未覆盖：

- **A. 外部导入**：团队已有 Confluence、Google Docs、旧 Wiki 中的文档，需要迁移到知识库
- **B. 元数据自举**：Discovery Engine 扫描数据库后已获取大量结构化信息（表名、列名、注释），这些应该自动生成知识条目草稿，解决冷启动
- **C. 知识反哺**：知识文档中包含结构化信息（术语定义、业务规则、指标公式），应该能反向创建/更新 Heirloom 元数据实体

三者方向不同但共用同一基础设施——模板引擎、格式转换器、Proposal 生成器。

---

## 决策

### 1. 总体架构：三条管道，一个引擎

```
┌────────────────────────────────────────────────────────────────────┐
│                     Knowledge Conversion Pipeline                  │
│                                                                    │
│  管道 A: 外部导入                                                  │
│  ┌──────────┐    ┌──────────────┐    ┌──────────┐                 │
│  │ Confluence │───▶│ HTML→MD     │───▶│          │                 │
│  │ Google Docs│───▶│ Converter   │───▶│          │                 │
│  │ Old Wiki  │───▶│             │───▶│  .md 文件 │───▶ knowledge/  │
│  └──────────┘    └──────────────┘    │          │    目录          │
│                                      │ 模板引擎  │                 │
│  管道 B: 元数据自举                 │          │                 │
│  ┌──────────┐    ┌──────────────┐    │ (Jinja/   │                 │
│  │ Metadata │───▶│ Template     │───▶│ Mustache) │                 │
│  │ Entity   │    │ Selector     │    │          │                 │
│  │(from     │    └──────────────┘    └──────────┘                 │
│  │ Discovery)                                                      │
│  └──────────┘                                                      │
│                                                                    │
│  管道 C: 知识反哺                                                  │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────┐             │
│  │ .md 文件 │───▶│ Pattern      │───▶│ Proposal     │───▶         │
│  │ (正文)   │    │ Extractor    │    │ Generator    │   Heirloom  │
│  └──────────┘    └──────────────┘    └──────────────┘   Entity    │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

三条管道**异步执行**，产生的结果写入同一文件系统（`knowledge/` 目录）。之后由 `KnowledgeSyncEngine` 统一同步到数据库索引。

### 2. 文件系统布局

```
knowledge/
├── _generated/                         # 自动生成区域
│   ├── discovery/                      # 管道 B：元数据自举
│   │   ├── tables/
│   │   │   ├── sales.orders.md         # "BigQuery Table" 类型
│   │   │   └── sales.customers.md
│   │   ├── lineage/
│   │   │   └── orders-to-shipments.md  # "Lineage" 类型
│   │   └── databases/
│   │       └── acme-sales.md           # "Database" 类型
│   └── imports/                        # 管道 A：外部导入
│       └── confluence/
│           ├── data-quality-runbook.md
│           └── onboarding-guide.md
├── tables/                             # 人类维护区域
│   └── orders.md                       # 可从 _generated/discovery 移出
├── playbooks/
│   └── incident-response.md
├── metrics/
│   └── revenue-ltv.md
├── index.md
└── log.md
```

**关键约定**：

| 目录 | 来源 | frontmatter 标记 | Git 行为 | 编辑者 |
|------|------|-----------------|---------|--------|
| `_generated/discovery/` | Discovery Engine 自动生成 | `source: heirloom-discovery` | 提交（可见、可审阅） | 人类可编辑 |
| `_generated/imports/` | 外部文档导入 | `source: heirloom-import-{source}` | 提交 | 人类可编辑 |
| 其他目录 | 人类手写 | `source` 字段不存在 | 提交 | 人类 |

`_generated/` 提交到 Git 的好处：
- 自动生成内容透明可见
- 可直接在文件中修改审阅（PR review）
- 无需额外「发布」步骤——改好就是正式内容

当人类确认草稿质量合格后：保持文件在原位（已经可以被搜索到），或移动到主目录。移动是可选的——`_generated/` 只是为了标记来源，不影响搜索和引用。

### 3. 管道 A：外部导入

#### 3.1 导入源接口

```java
/**
 * 外部知识导入源——可插拔。
 * 每个实现负责从外部系统读取内容并转换为 Markdown。
 */
public interface KnowledgeImporter {
    /** 导入源类型标识 */
    String sourceType();

    /** 从外部源读取所有可导入条目 */
    List<ImportEntry> listEntries(ImportConfig config);

    /** 将单个条目转换为 Markdown 内容（含 frontmatter） */
    String convertToMarkdown(ImportEntry entry);
}

public record ImportEntry(
    String id,                    // 外部系统中的 ID
    String title,
    String content,               // 原始内容（HTML、Wiki markup 等）
    String contentType,           // "html" | "wiki" | "markdown" | "plain"
    String url,                   // 原始 URL
    List<String> tags,
    Instant lastModified
) {}

public record ImportConfig(
    String sourceUrl,             // Confluence endpoint、folder path 等
    Map<String, String> auth,     // API token、username/password
    String spaceFilter,           // Confluence space、Google Drive folder
    boolean includeAttachments,
    Instant modifiedSince         // 增量导入
) {}
```

#### 3.2 内置导入器（Phase 0.5 提供 HTML→MD，其余按需扩展）

| 导入器 | sourceType | 输入格式 | 输出 |
|--------|-----------|---------|------|
| `HtmlImporter` | `html` | HTML 文件/字符串 | Markdown（Turndown 或类似引擎） |
| `ConfluenceImporter` | `confluence` | Confluence REST API | 每页一个 .md 文件 |
| `GoogleDocsImporter` | `google-docs` | Google Docs API | 每文档一个 .md 文件 |
| `MarkdownImporter` | `markdown` | 现有 .md 文件（不同目录/仓库） | 直接复制 + 补充 frontmatter |

#### 3.3 导入流程

```
POST /v1/knowledge/sources/{sourceId}/import
Request:
  {
    "importerType": "confluence",
    "config": {
      "sourceUrl": "https://acme.atlassian.net/wiki",
      "auth": {"token": "..."},
      "spaceFilter": "DATA",
      "includeAttachments": false,
      "modifiedSince": "2026-01-01T00:00:00Z"
    },
    "targetDir": "imports/confluence"    // 相对于 knowledge/ 的目录
  }

流程:
  1. 创建 ImportReport { status: IN_PROGRESS }
  2. 调用对应 Importer.listEntries() 获取外部条目列表
  3. 对每个条目:
     a. 调用 Importer.convertToMarkdown() 生成 .md 内容
     b. 写入 knowledge/{targetDir}/{entry.title}.md
     c. 自动添加 frontmatter:
        ---
        type: Imported Document
        title: {entry.title}
        source: heirloom-import-confluence
        source_url: {entry.url}
        imported_at: {now}
        last_source_modified: {entry.lastModified}
        tags: {entry.tags}
        ---
     d. 记录进度
  4. 写入完成后，触发 KnowledgeSyncEngine.sync(sourceFqn)
  5. 返回 ImportReport
```

#### 3.4 增量更新

```
GET /v1/knowledge/sources/{sourceId}/import/diff
  # 对比外部源和已导入文件的 last_source_modified 时间戳
  # 返回：new, updated_in_source, deleted_in_source 三个列表
```

### 4. 管道 B：元数据自举

#### 4.1 触发时机

Discovery Engine 完成扫描后，已创建/更新了元数据实体（Table、Column、Lineage）。此时通过 **Spring 事件机制**触发知识生成。

**前提：需要重构 `DiscoveryService.runDiscovery()` 增加事件发布**

```java
// DiscoveryService.java 末尾添加
@Autowired
private ApplicationEventPublisher eventPublisher;

public DiscoveryReport runDiscovery(DiscoverySource source) {
    // ... existing discovery logic ...
    
    // 发布完成事件（新增）
    eventPublisher.publishEvent(new DiscoveryCompletedEvent(
        source.getFullyQualifiedName(),
        discoveredTables,
        discoveredLineages
    ));
    
    return report;
}
```

接收端在 `KnowledgeBootstrapper` 中：

```java
@Component
public class KnowledgeBootstrapper {

    /**
     * 监听 Discovery 完成事件，自动为发现的新表/血缘生成知识草稿。
     * 使用 @EventListener 实现松耦合——KnowledgeBootstrapper 不修改 DiscoveryService。
     */
    @EventListener
    public void onDiscoveryCompleted(DiscoveryCompletedEvent event) {
        KnowledgeSource activeSource = findActiveKnowledgeSource();
        if (activeSource == null) {
            log.info("No active KnowledgeSource — skipping bootstrap");
            return;
        }
        
        BootstrapReport report = bootstrapFromMetadata(
            event.getTables(), event.getLineages(), activeSource.getPath());
        
        log.info("Bootstrap completed: {} tables, {} lineages generated",
            report.getTablesGenerated(), report.getLineagesGenerated());
    }
        List<TableEntity> tables,
        List<LineageEntity> lineages,
        String knowledgeSourcePath
    ) {
        BootstrapReport report = new BootstrapReport();

        // 对每个 Table，生成知识条目草稿
        for (TableEntity table : tables) {
            String mdContent = templateEngine.render("table-knowledge", toTableModel(table));
            Path outputPath = Path.of(knowledgeSourcePath,
                "_generated/discovery/tables",
                tableSafename(table) + ".md");
            writeFile(outputPath, mdContent);
            report.incrementTables();
        }

        // 对每条 Lineage，生成知识条目草稿
        for (LineageEntity lineage : lineages) {
            String mdContent = templateEngine.render("lineage-knowledge", toLineageModel(lineage));
            Path outputPath = Path.of(knowledgeSourcePath,
                "_generated/discovery/lineage",
                lineageSafename(lineage) + ".md");
            writeFile(outputPath, mdContent);
            report.incrementLineages();
        }

        return report;
    }
}
```

#### 4.2 模板系统

使用 Mustache（Java 生态最轻量），每个实体类型一个模板。生产者可以自定义模板。

**Maven 坐标**：`com.github.spullara.mustache.java:compiler:0.9.14`（零额外依赖，与 Spring Boot 无冲突）。
选型理由：Heirloom 项目当前未使用 Thymeleaf 或 FreeMarker——引入 Mustache 不造成模板引擎冲突。

**默认模板 `table-knowledge.mustache`**：

```markdown
---
type: BigQuery Table
title: {{tableName}}
description: Auto-generated knowledge entry for {{fullyQualifiedName}}.
source: heirloom-discovery
source_table: {{fullyQualifiedName}}
discovered_at: {{discoveredAt}}
tags: [auto-generated, {{domain}}]
---

# Schema

This table was discovered from {{databaseServiceType}} at `{{databaseServiceFQN}}`.

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
{{#columns}}
| `{{name}}` | {{dataType}} | {{#nullable}}✅{{/nullable}}{{^nullable}}❌{{/nullable}} | {{description}} |
{{/columns}}

{{#primaryKey.columns.size}}
## Primary Key
{{primaryKey.columns}} ({{primaryKey.type}})
{{/primaryKey.columns.size}}

# Usage Notes

<!-- TODO: Add business context. How is this table used? What does each column mean in business terms? -->

# References

* [Table metadata](@{{fullyQualifiedName}})
{{#referencedTables}}
* [Referenced table: {{name}}](@{{fqn}})
{{/referencedTables}}
```

**模板上下文模型**：

```java
public record TableTemplateModel(
    String tableName,
    String fullyQualifiedName,
    String domain,
    String databaseServiceFQN,
    String databaseServiceType,
    String discoveredAt,
    List<ColumnModel> columns,
    PrimaryKeyModel primaryKey,
    List<ReferencedTableModel> referencedTables
) {}

public record ColumnModel(String name, String dataType, boolean nullable, String description) {}
public record PrimaryKeyModel(List<String> columns, String type) {}
public record ReferencedTableModel(String name, String fqn) {}
```

#### 4.3 自举策略

| 策略 | 行为 | 使用场景 |
|------|------|---------|
| **仅新表**（默认） | 只为首次发现的表生成知识条目 | 日常增量扫描 |
| **全量重生成** | 覆盖所有已生成的知识条目（保留人类编辑的内容通过 hash 检测） | 模板更新后批量刷新 |
| **仅更新元数据区块** | 只更新 `# Schema` 和 `# Primary Key` 区块，保留人类的 `# Usage Notes` | 表结构变更后自动同步列清单 |

**策略 3（仅更新元数据区块）的算法**：

```
当表结构变更时:
  1. 解析已有 .md 文件
  2. 识别 "保护区" 和非保护区：
     - 保护区：用户在特定区块写的自定义内容（# Usage Notes、# Examples 等）
     - 非保护区：# Schema、# Primary Key（结构数据，可自动覆盖）
  3. 重新渲染非保护区 → 合并保护区的原始内容 → 写回文件
  4. 如果 human-modified-hash 与上次相同 → 说明人类没改过 → 可安全覆盖全文
```

**区块标记（Markdown 注释，对渲染无影响）**：

```markdown
<!-- HEIRLOOM_AUTO_START: schema -->
# Schema
| Column | Type | ...
<!-- HEIRLOOM_AUTO_END: schema -->

<!-- HEIRLOOM_AUTO_START: primary-key -->
## Primary Key
...
<!-- HEIRLOOM_AUTO_END: primary-key -->

# Usage Notes
<!-- Everything outside AUTO blocks is human-edited and preserved -->
This table tracks customer orders. The `status` column follows the order
lifecycle defined in [Order Lifecycle Policy](/policies/order-lifecycle.md).
```

#### 4.4 自举控制 API

```
POST /v1/knowledge/sources/{sourceId}/bootstrap
Request:
  {
    "strategy": "new_tables_only",      // "new_tables_only" | "full_regenerate" | "update_metadata_blocks"
    "entityTypes": ["table", "lineage"],// 只生成指定类型
    "dryRun": false                     // true = 仅预览不写入
  }
Response 200:
  {
    "status": "COMPLETED",
    "generated": {
      "tables": 12,
      "lineages": 3
    },
    "updated": {
      "tables": 2                       // metadata_blocks 模式下的部分更新
    },
    "skipped": {
      "tables": 45                      // 已存在且未变更的
    },
    "dryRun": false
  }
```

### 5. 管道 C：知识反哺

#### 5.1 架构定位

管道 C 在 KnowledgeSyncEngine 同步完成**之后**异步执行。它不是实时转换，而是定期扫描知识条目的正文内容，识别结构化模式，生成 Proposal。

```java
/**
 * 知识反哺引擎——从知识条目正文中提取结构化实体。
 * 产生 Proposal（而非直接创建实体），保留人类审批环节。
 */
public interface KnowledgePromoter {

    /** 从单个知识条目中提取可提升的实体 */
    List<PromotionCandidate> extract(KnowledgeArticle article);

    /** 将 PromotionCandidate 转换为 Proposal */
    Proposal generateProposal(PromotionCandidate candidate);
}

public record PromotionCandidate(
    String sourceArticleFqn,     // 来源知识条目
    String candidateType,        // "glossaryTerm" | "businessRule" | "metric" | "function"
    String name,
    String description,
    Map<String, Object> properties,  // 实体特定属性
    String sourceText,           // 在正文中的原始文本
    int confidence               // 0-100
) {}
```

#### 5.2 内置提取器

| 提取器 | 识别模式 | 目标实体 | 示例 |
|--------|---------|---------|------|
| `GlossaryExtractor` | `**{Term}**：{定义}` 或 `### {Term}` + 段落 | `GlossaryTerm`（元数据层） | `**活跃客户**：过去 30 天有至少一笔交易的客户` |
| `BusinessRuleExtractor` | fenced code block 含 `rule_id:` frontmatter | `BusinessRule`（知识层内结构化版本） | 见下方示例 |
| `MetricExtractor` | `type: Metric` 知识条目 | `Metric` 实体（知识层内） | LTV、Revenue 等指标定义 |
| `FunctionExtractor` | SQL 代码块 + 描述 | `Function`（语义层） | `risk_score()` 函数 |

#### 5.3 GlossaryTerm 提取示例

**知识条目正文**：

```markdown
# 关键概念

**活跃客户 (Active Customer)**：过去 30 天内有至少一笔已完成订单的客户。
与「注册客户」不同——注册客户可能从未下单。

**客户终身价值 (LTV)**：客户从首次下单到最近一次下单之间的累计消费金额。
按 [LTV 计算指标](/metrics/revenue-ltv.md) 执行。
```

**提取结果**：

```java
PromotionCandidate(
    sourceArticleFqn = "knowledge.primary.concepts.customer-definitions",
    candidateType = "glossaryTerm",
    name = "活跃客户",
    description = "过去 30 天内有至少一笔已完成订单的客户",
    properties = {
        "synonyms": ["Active Customer"],
        "relatedTerms": ["注册客户"]
    },
    sourceText = "**活跃客户 (Active Customer)**：过去 30 天内有至少一笔...",
    confidence = 85
)
```

#### 5.4 反哺流程

```
KnowledgePromotionEngine.run(sourceFqn)
│
├─ 1. 查询所有知识条目（syncStatus = OK）
│
├─ 2. 对每条知识条目，运行所有提取器
│   ├─ GlossaryExtractor.extract(article)
│   ├─ BusinessRuleExtractor.extract(article)
│   ├─ MetricExtractor.extract(article)
│   └─ FunctionExtractor.extract(article)
│
├─ 3. 去重与合并
│   ├─ 同一术语在多个文章中出现 → 合并 description
│   └─ 与已有实体对比 → 新增 vs 更新
│
├─ 4. 对每个 PromotionCandidate（confidence >= 阈值）:
│   └─ 生成 Proposal → proposalRepo.create()
│      Proposal {
│        targetEntityType: "glossaryTerm",  // 或 "metric" 等
│        proposedChanges: { name, description, ... }
│        source: "knowledge-promotion",
│        status: "PENDING"                  // 人类审批
│      }
│
└─ 5. 返回 PromotionReport
```

#### 5.5 反哺控制 API

```
POST /v1/knowledge/promote
Request:
  {
    "extractors": ["glossary", "metric"],   // 只运行指定提取器
    "minConfidence": 70,
    "dryRun": false
  }
Response 200:
  {
    "status": "COMPLETED",
    "candidates": {
      "glossary": [
        {
          "name": "活跃客户",
          "confidence": 85,
          "sourceArticle": "knowledge.primary.concepts.customer-definitions",
          "proposalId": 42,
          "proposalStatus": "PENDING"
        }
      ],
      "metric": [...]
    },
    "totalCandidates": 5,
    "proposalsCreated": 3,
    "duplicatesSkipped": 2
  }
```

### 6. 统一管道调度

三条管道可以独立触发，也可以通过统一的调度框架编排：

```java
/**
 * 统一管道调度器——编排 A/B/C 三条管道的执行顺序。
 * 
 * 典型编排：
 *   Discovery 完成 → 管道 B（自举）→ 管道 C（反哺）→ 通知
 *   外部导入   → 管道 A → 管道 C（反哺新导入的内容）
 */
public class ConversionPipelineOrchestrator {

    /**
     * Discovery 后自动触发 B + C
     */
    @EventListener
    public void onDiscoveryCompleted(DiscoveryCompletedEvent event) {
        // B: 自举——为发现的新表生成知识条目
        BootstrapReport bootstrap = bootstrapper.bootstrapFromMetadata(
            event.getTables(), event.getLineages(), knowledgeSourcePath);
        
        // 同步文件到索引
        syncEngine.sync(knowledgeSourceFqn);
        
        // C: 反哺——从知识条目中提取结构化实体
        PromotionReport promotion = promoter.promote(knowledgeSourceFqn);
        
        // 通知
        log.info("Pipeline B+C completed: {} bootstrapped, {} promoted",
            bootstrap.totalGenerated(), promotion.totalCandidates());
    }
}
```

### 7. 新增实体与迁移

#### 7.1 管道元数据实体

```java
// 导入报告——追踪外部导入的历史
@Entity @Table(name = "knowledge_import_reports")
public class ImportReport implements HeirloomEntity {
    // sourceFqn, importerType, status, entriesTotal, entriesImported, 
    // entriesFailed, startedAt, completedAt, ...
}

// 自举报告——追踪元数据自举的历史
@Entity @Table(name = "knowledge_bootstrap_reports")
public class BootstrapReport implements HeirloomEntity {
    // sourceFqn, strategy, tablesGenerated, tablesUpdated, tablesSkipped, ...
}

// 反哺报告——追踪知识→实体转换的历史
@Entity @Table(name = "knowledge_promotion_reports")
public class PromotionReport implements HeirloomEntity {
    // sourceFqn, candidatesTotal, proposalsCreated, duplicatesSkipped, ...
}
```

#### 7.2 数据库迁移

```sql
-- V4__knowledge_conversion_pipeline.sql

CREATE TABLE IF NOT EXISTS knowledge_import_reports (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256),
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_fqn VARCHAR(512) NOT NULL,              -- FK to knowledge_sources
  importer_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) DEFAULT 'IN_PROGRESS',
  entries_total INTEGER DEFAULT 0,
  entries_imported INTEGER DEFAULT 0,
  entries_failed INTEGER DEFAULT 0,
  entries_skipped INTEGER DEFAULT 0,
  error_summary JSONB DEFAULT '[]',
  started_at TIMESTAMPTZ DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS knowledge_bootstrap_reports (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256),
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_fqn VARCHAR(512) NOT NULL,
  strategy VARCHAR(64) NOT NULL,                  -- 'new_tables_only' | 'full_regenerate' | 'update_metadata_blocks'
  status VARCHAR(32) DEFAULT 'IN_PROGRESS',
  tables_generated INTEGER DEFAULT 0,
  tables_updated INTEGER DEFAULT 0,
  tables_skipped INTEGER DEFAULT 0,
  lineages_generated INTEGER DEFAULT 0,
  error_summary JSONB DEFAULT '[]',
  started_at TIMESTAMPTZ DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS knowledge_promotion_reports (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(256),
  fully_qualified_name VARCHAR(512) NOT NULL UNIQUE,
  source_fqn VARCHAR(512) NOT NULL,
  status VARCHAR(32) DEFAULT 'IN_PROGRESS',
  candidates_total INTEGER DEFAULT 0,
  proposals_created INTEGER DEFAULT 0,
  duplicates_skipped INTEGER DEFAULT 0,
  candidate_details JSONB DEFAULT '[]',
  started_at TIMESTAMPTZ DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  version BIGINT DEFAULT 1,
  change_hash VARCHAR(64),
  deleted BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

### 8. 分阶段计划

#### Phase 0.5：管道 B 基础（自举）

- [ ] Mustache 模板引擎集成
- [ ] `table-knowledge.mustache` 默认模板
- [ ] `KnowledgeBootstrapper`：Table → .md 文件（全量重生成策略）
- [ ] Discovery 完成后自动触发自举
- [ ] API：`POST /v1/knowledge/sources/{id}/bootstrap`

#### Phase 1：管道 A 基础（导入）+ 管道 B 增强

- [ ] `HtmlImporter`（HTML → Markdown）
- [ ] `ConfluenceImporter`（Confluence REST API → .md）
- [ ] 自举增量策略：`update_metadata_blocks`（仅更新 Schema 区块，保留人类内容）
- [ ] HEIRLOOM_AUTO_START/END 区块标记

#### Phase 2：管道 C 基础（反哺）

- [ ] `GlossaryExtractor`（`**{Term}**：{定义}` 模式）
- [ ] `MetricExtractor`（`type: Metric` 知识条目）
- [ ] Proposal 生成 → 人类审批流程
- [ ] API：`POST /v1/knowledge/promote`

#### Phase 3：管道增强

- [ ] `BusinessRuleExtractor`（结构化 YAML 区块）
- [ ] `FunctionExtractor`（SQL → Function 定义）
- [ ] 管道 C 提升置信度模型（多文章交叉验证）

---

## 后果

### 积极

- **冷启动解决**：Discovery 后自动生成知识草稿，不再从零开始
- **文档迁移有路**：Confluence/旧 Wiki 可批量导入
- **双向桥建成**：知识层和元数据层相互引用、相互促进
- **渐进采纳**：自动生成 → 人类审阅 → 人类增强 → 反哺实体，形成正向飞轮

### 消极

- **管道复杂度**：三条管道 + 统一调度，增加了运维心智负担
- **模板维护成本**：不同实体类型需要不同模板，模板随实体模型演化
- **反哺误报**：模式提取可能产生低质量 Proposal，需要好的置信度阈值和 UI 支持批量审批
- **区块标记约定**：HEIRLOOM_AUTO_START/END 标记需要在模板中内置，人机协作需要文档对齐

---

## 备选方案

### 方案 A：不做反哺——知识层纯粹被动索引

**放弃理由**：知识条目的价值在于被 Agent 和人类消费。如果不提取结构化实体，Agent 每次都需要读取全文才能理解术语含义。提取为 GlossaryTerm 后，Agent 可直接查询术语定义，效率更高。

### 方案 B：自举直接写入数据库而非文件

**放弃理由**：与「文件是真相源」前提矛盾。直接写数据库会产生数据库有、文件无的「幽灵条目」，破坏单向同步的简洁性。

---

## 参考

- ADR-032: Knowledge Base Module — Architecture Design
- ADR-032b: Knowledge Base Module — Deep Dive
- ADR-002: Ability 作为类型层契约
- ADR-005: 九步 Action 校验流水线
