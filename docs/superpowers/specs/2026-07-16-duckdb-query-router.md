# Heirloom DuckDB Raw Store + Query Router — 设计规格

**日期**: 2026-07-16
**状态**: Draft
**参考**: ADR-037, ADR-040, ADR-043, ADR-044, ADR-046

---

## 1. 背景

Phase 0-4 完成了元数据层基础建设（模块化 + 实体补齐 + Profiling + InferencePipeline 升级）。按 ADR-046 路线图，Phase 5 实现 DuckDB raw store + Query Router + DSL 扩展——为数据分析师和 AI Agent 提供对 raw 数据的自由查询能力。

本设计基于 ADR-037（DuckDB raw store）和 ADR-040（Query Router），在 Phase 0-4 基础上做了以下调整：
- DuckDB 同步范围收窄为「仅已注册的 metadata_tables」（利用 Phase 1 的 Discovery 结果）
- Freshness 检查复用 Phase 2 的 `ColumnProfileEntity.profiledAt`（不建独立 freshness 表）
- DuckDB 放在 heirloom-server 内部（不作为独立模块）
- 消费层仅做 REST API（SDK/Workshop 推迟）

---

## 2. 架构总览

```
REST API (POST /v1/query)
        │
        ▼
┌───────────────┐
│  QueryRouter  │  mode=semantic|raw|hybrid|auto
└───────┬───────┘
        │
   ┌────┴────┐
   ▼         ▼
Semantic   DuckDB
Layer      RawStore
   │         │
   ▼         ▼
Source PG  .db files
(Mapping   (per-tenant
 Engine)    disk cache)
```

**模块位置**：全部在 `heirloom-server` 内部，不新建 Maven 模块。

```
heirloom-core/src/main/java/com/heirloom/core/query/
├── QueryMode.java                  # NEW
├── QueryRequest.java               # NEW
├── QueryResult.java                # NEW
├── RouteDecision.java              # NEW
├── RouteStep.java                  # NEW
├── ResourceRef.java                # NEW
├── DrillDown.java                  # NEW
├── QueryPayload.java               # NEW
├── SemanticQuery.java              # existing (Phase 0)
└── SqlGenerator.java               # existing interface (Phase 0)

heirloom-server/src/main/java/com/heirloom/
├── duckdb/
│   ├── DuckDbRawStore.java
│   ├── DuckDbSyncService.java
│   ├── DuckDbNaming.java
│   ├── SyncResult.java
│   ├── FreshnessStatus.java
│   ├── FreshnessChecker.java
│   └── web/DuckDbResource.java
├── query/
│   ├── QueryRouter.java
│   ├── SemanticExecutor.java
│   └── RawQueryAuthorizer.java
└── web/
    └── QueryController.java (修改)
```

---

## 3. DuckDB Raw Store

### 3.1 DuckDbRawStore

管理嵌入式 DuckDB `.db` 文件。MVP 单租户——单文件存储在 `data/heirloom_raw.db`。

```java
@Service
public class DuckDbRawStore implements AutoCloseable {

    private final Connection conn;

    public DuckDbRawStore(@Value("${heirloom.duckdb.url:jdbc:duckdb:data/heirloom_raw.db}") String url) {
        this.conn = DriverManager.getConnection(url);
    }

    public List<Map<String, Object>> query(String sql) { ... }
    public synchronized void execute(String sql) { ... }
    public boolean tableExists(String tableName) { ... }

    @Override
    public void close() { conn.close(); }
}
```

**线程安全**：`execute()` 用 `synchronized` 保护（DuckDB 嵌入式不支持多连接并发写入）。`query()` 无锁。

### 3.2 DuckDbSyncService

从源 PG 拉取已注册表到 DuckDB。

- 只同步 `metadata_tables` 中已注册的表——安全边界，拒绝同步未注册表
- 表命名：DuckDB 中为 `_raw_` + sanitized FQN（如 `_raw_public_orders`）
- 批量写入使用 DuckDB `Appender` API（比逐行 INSERT 快 10x）

```java
@Service
public class DuckDbSyncService {

    public SyncResult sync(String tableFQN) {
        TableEntity table = tableRepo.findByFQN(tableFQN).orElseThrow();
        List<ColumnDef> columns = ColumnDefParser.parse(table.getColumnsJson());

        String duckDbName = DuckDbNaming.toDuckDbName(tableFQN);
        String tmpName = duckDbName + "_tmp";

        // 1. 在临时表中创建新数据（不阻塞读）
        String createSql = buildCreateTableSql(columns);
        duckDb.execute("DROP TABLE IF EXISTS \"" + tmpName + "\"");
        duckDb.execute(createSql.replace(duckDbName, tmpName));

        // 2. 从源 PG 批量写入临时表
        long rowCount = appendFromPostgres(tableFQN, columns, tmpName);

        // 3. 原子替换：DROP 旧表 → RENAME 新表（最小化窗口期）
        duckDb.execute("DROP TABLE IF EXISTS \"" + duckDbName + "\"");
        duckDb.execute("ALTER TABLE \"" + tmpName + "\" RENAME TO \"" + duckDbName + "\"");

        // 4. 追踪同步记录
        saveSyncTrace(tableFQN, rowCount);

        return new SyncResult(tableFQN, rowCount, duration);
    }
```

**并发安全**：两层保护——(1) `ConcurrentHashMap<String, Object>` 表级锁，同表不可并发同步；(2) CREATE tmp → DROP old → RENAME 原子替换。读查询在表不存在时短暂窗口期返回错误（而非读到半写入数据），QueryRouter 层自动重试 1 次。

### 3.3 同步触发

| 触发方式 | 场景 | MVP |
|---------|------|:---:|
| Raw 查询时按需同步 | Freshness 过期或表不存在 | ✅ |
| API 手动触发 | `POST /v1/duckdb/sync/{tableFQN}` | ✅ |
| Discovery 后自动同步 | Phase 6 | ❌ |
| 定时刷新 | Phase 6 | ❌ |

### 3.4 FreshnessChecker

复用 Phase 2 的 `column_profiles` 表——不新建独立 freshness 表。DuckDbSyncService 每次同步后插入特殊记录（`columnName='_row_count'`）。

```java
@Component
public class FreshnessChecker {

    @Value("${heirloom.duckdb.freshness.ttl-minutes:5}")
    private int ttlMinutes;

    public boolean isFresh(String tableFQN) {
        if (!duckDb.tableExists(toDuckDbName(tableFQN))) return false;
        var latest = profileRepo
            .findByTableFQNAndColumnNameOrderByProfiledAtDesc(tableFQN, "_row_count")
            .stream().findFirst();
        if (latest.isEmpty()) return false;
        return latest.get().getProfiledAt()
            .isAfter(Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES));
    }
}
```

### 3.5 saveSyncTrace 方法

DuckDbSyncService 每次同步完成后调用，在 `column_profiles` 表中写入表级追踪记录：

```java
private void saveSyncTrace(String tableFQN, long rowCount) {
    var trace = new ColumnProfileEntity();
    trace.setTableFQN(tableFQN);
    trace.setColumnName("_row_count");  // 特殊标记：表级同步追踪（非真实列）
    trace.setProfiledAt(Instant.now());
    trace.setNullCount(rowCount);       // 复用存行数
    profileRepo.save(trace);
}
```

`FreshnessChecker` 通过查询 `columnName='_row_count'` 的最新 `profiledAt` 判断新鲜度。

> **已知局限**：将同步追踪和列级 profiling 数据混存同一表。如果未来 profiling 数据量大或需要独立的同步审计表，可拆分为 `duckdb_sync_log`。MVP 阶段复用 column_profiles 避免新建表。

### 3.6 MVP 已知局限

以下 ADR-037 设计点在 MVP 中有意简化：

| ADR-037 要求 | MVP 决策 | 理由 |
|-------------|---------|------|
| `DuckDbStorageBackend` 接口（本地/对象存储切换） | 硬编码文件路径 `data/heirloom_raw.db` | 多租户在 Phase 7，单租户不需要抽象 |
| 数据加密（静态数据安全） | 不做 | 依赖文件系统权限隔离，合规治理在 Phase 7 |
| 数据保留/生命周期（TTL 清理） | 不做自动清理 | 手动 `POST /v1/duckdb/sync/{fqn}` 覆盖同步 |
| 采样配置 `sample-threshold` / `sample-rate` | 暂存 yml 配置 | DuckDbSyncService 读取配置，> threshold 行使用 `TABLESAMPLE` |

---

## 4. QueryRouter

### 4.1 路由决策逻辑（AUTO 模式）

```
1. 含 rawTable / rawSql                                 → RAW
2. 含 resource + drillDown                              → HYBRID
3. payload 含 aggregate/groupBy/window 等分析操作           → RAW（语义层不支持复杂分析）
4. type 是已发布的 ResourceType（即在 `resource_types` 表中存在且 `deleted=false`） → SEMANTIC
5. 无法判断                                              → 返回 400，提示指定 mode
```

步骤 3 新增：当查询操作包含聚合函数、GROUP BY、窗口函数、跨表 JOIN 等语义层无法表达的操作时，自动路由到 RAW。实现方式：检查 `SemanticQuery` 的 `aggregate` 和 `traverse` 字段——如果 `aggregate` 含 `$sum/$avg/$min/$max` 或存在多重嵌套 traverse，触发 RAW 回退。

### 4.2 核心类型

```java
public enum QueryMode { SEMANTIC, RAW, HYBRID, AUTO }

public record QueryRequest(
    QueryMode mode,         // 默认 AUTO
    QueryPayload payload,   // semantic: JSON DSL
    String rawTable,        // raw: 用户可见的源表 FQN（如 "prod.pg.public.orders"）
    String rawSql,          // raw: 直接 SQL（可选）
    ResourceRef resource,   // hybrid: semantic 入口
    DrillDown drillDown     // hybrid: raw 下钻
) {}

public record RouteDecision(
    QueryMode mode,
    QueryPayload payload,
    String rawTable,
    String rawSql,
    ResourceRef resource,
    DrillDown drillDown,
    List<RouteStep> steps   // hybrid 时拆为多步
) {}

public record RouteStep(
    String engine,                    // "semantic" | "duckdb"
    QueryPayload payload,             // 该步的实际查询
    Map<String, Object> bindings      // 上一步产出 → 下一步参数绑定
) {}

public record ResourceRef(
    String type,        // ResourceType name (e.g., "Customer")
    String rid,         // Resource instance RID (e.g., "default.Customer.abc123")
    List<String> fields // fields to retrieve (e.g., ["customer_id", "email"])
) {}

public record DrillDown(
    String rawTable,    // target raw table FQN
    String rawSql       // SQL with :bind_name placeholders
) {}

public record QueryResult(
    List<Map<String, Object>> rows,
    long totalCount,
    QueryMode resolvedMode,
    boolean fresh,
    Long executionTimeMs,
    Map<String, Object> meta,    // freshness info etc.
    Map<String, Object> bindings // HYBRID: values extracted from step 1 for step 2
) {}
```

### 4.3 HYBRID 模式执行

```java
QueryResult executeHybrid(RouteDecision decision) {
    // Step 1: 语义查询获取绑定值（如解析 Customer RID → customer_id）
    var resourceResult = semanticExecutor.execute(decision.payload());

    // Step 2: 注入绑定值到 raw SQL（:customer_id → "C-456"）
    String resolvedSql = resolveBindings(decision.drillDown().rawSql(),
                                          resourceResult.bindings());

    // Step 3: Freshness 检查 + 必要时同步（同步失败不阻塞）
    if (!freshness.isFresh(decision.rawTable())) {
        try { syncService.sync(decision.rawTable()); }
        catch (Exception e) { log.warn("Sync failed for {}, using stale data", decision.rawTable(), e); }
    }
    authorizer.check(decision.rawTable(), resolvedSql);

    // Step 4: DuckDB 执行
    List<Map<String, Object>> rows = duckDb.query(resolvedSql);
    return new QueryResult(rows, rows.size(), QueryMode.HYBRID,
        freshness.isFresh(decision.rawTable()), null, Map.of(), resourceResult.bindings());
}

/** 将 SQL 中的 :param_name 占位符替换为实际值 */
private String resolveBindings(String sql, Map<String, Object> bindings) {
    for (var entry : bindings.entrySet()) {
        String placeholder = ":" + entry.getKey();
        String value = entry.getValue().toString();
        sql = sql.replace(placeholder, "'" + value.replace("'", "''") + "'");
    }
    return sql;
}
```

### 4.4 SemanticExecutor

从现有 `QueryController` 中提取语义查询执行逻辑为独立 service：

```java
@Service
public class SemanticExecutor {
    public QueryResult execute(QueryPayload payload) {
        // 现有 QueryController.query() 的逻辑：
        // 1. 解析 JSON DSL → SemanticQuery
        // 2. Perspective 过滤
        // 3. SqlGenerator 生成 SQL
        // 4. JDBC 执行
        // 5. 返回结果
    }
}
```

**提取策略**：`QueryController` 现有 `query()` 方法的逻辑（QueryParser → PerspectiveEngine → SqlGenerator → JdbcTemplate）整体移入 `SemanticExecutor`。`QueryController` 改为薄层——接收请求，委托给 `queryRouter.execute()`。

### 4.5 RawQueryAuthorizer

```java
@Component
public class RawQueryAuthorizer {

    private final TableRepository tableRepo;

    public void check(String tableFQN, String sql) {
        // 1. 白名单：表必须在 metadata_tables 中注册
        if (!tableRepo.existsByFQN(tableFQN)) {
            throw new UnauthorizedException("Raw query not allowed on unregistered table: " + tableFQN);
        }
        // 2. 移除前导空白和注释，**丢弃首句后的所有内容**（防 `;` 链式攻击）
        String cleaned = stripComments(sql.trim());
        int semiIdx = cleaned.indexOf(';');
        String firstStmt = semiIdx >= 0 ? cleaned.substring(0, semiIdx).trim() : cleaned.trim();
        // 3. 禁止 DDL/DML（只允许 SELECT/WITH/EXPLAIN）
        String upper = firstStmt.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH") && !upper.startsWith("EXPLAIN")) {
            throw new UnauthorizedException("Only SELECT queries allowed in raw mode");
        }
        // 4. 单次查询行数限制（防止全表大查询）
        if (!upper.contains("LIMIT")) {
            throw new UnauthorizedException("Raw query must include LIMIT clause");
        }
    }

    private String stripComments(String sql) {
        // 移除 -- 行注释
        sql = sql.replaceAll("--[^\n]*", "");
        // 移除 /* */ 块注释
        sql = sql.replaceAll("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", "");
        return sql;
    }
}
```

> **LIMIT 增强（非 MVP）**：当前仅检查关键字存在，不解析 LIMIT 值。例如 `LIMIT 1000000000` 可通过检查。Phase 6 可解析 SQL ACTUAL LIMIT 值并用 `max-rows` 配置上限。
```

> **ADR-040 对比**：ADR-040 定义了完整的 `RawQueryRestrictions` 模型（allowedTables/deniedTables/allowedColumns/allowAggregation/allowJoin/maxRows），绑定到 Role 配置。MVP 简化为三规则（白名单 + DDL拦截 + LIMIT要求），Role 级细粒度权限留 Phase 6。当前阶段 RawQueryAuthorizer 不接受外部配置——所有已注册表默认允许只读 SELECT。

### 4.6 表名映射

用户请求中的 `rawTable` 是源表 FQN（如 `"prod.pg.public.orders"`）。DuckDB 内部表名为 `"_raw_"` + sanitized FQN（`.` 替换为 `_`）：

```java
// 转换规则
"prod.pg.public.orders" → "_raw_prod_pg_public_orders"
```

`DuckDbNaming.toDuckDbName(fqn)` 和 `fromDuckDbName(name)` 两个静态方法统一转换。

**用户 rawSql 中的表引用**：QueryRouter 在路由到 RAW 模式时自动将源 FQN 替换为 DuckDB 内部表名。用户可以在 `rawSql` 中使用 `{table}` 占位符（如 `"SELECT * FROM {table} WHERE id > 100"`），QueryRouter 执行前替换 `{table}` → `_raw_prod_pg_public_orders`。如果用户直接写了内部表名（如 `_raw_prod_pg_public_orders`），不做二次替换。

DuckDB 内所有 raw 表都是扁平的单表（无 schema namespace），所以 `FROM _raw_prod_pg_public_orders` 直接可用，不需要 `schema.table` 限定。

### 4.7 错误处理

| 场景 | HTTP 状态码 | 错误消息 |
|------|:---:|------|
| AUTO 模式无法判断路由 | 400 | `"Cannot determine query mode. Specify mode=semantic|raw|hybrid"` |
| raw 表未在 metadata_tables 注册 | 403 | `"Raw query not allowed on unregistered table: {fqn}"` |
| SQL 含 DDL/DML | 403 | `"Only SELECT queries allowed in raw mode"` |
| SQL 不含 LIMIT | 403 | `"Raw query must include LIMIT clause"` |
| DuckDB 表需要同步但同步失败 | 502 | `"Failed to sync table {fqn}: {reason}"` |
| DuckDB JDBC 连接失败 | 502 | `"DuckDB unavailable: {reason}"` |
| Freshness 过期但同步也失败 | 200 + `{"fresh":false}` | 返回过期数据，附带 freshness warning 在 meta 中 |

所有错误遵循现有 `GlobalExceptionHandler` 的 RFC 7807 ProblemDetail 格式。

### 4.8 模块位置

接口和 record 类型（`QueryMode`、`QueryRequest`、`QueryResult`、`RouteDecision`、`RouteStep`）定义在 `heirloom-core/.../query/`，与现有 `SemanticQuery`、`SqlGenerator` 同包。Advisor 和统计：

实现类（`QueryRouter`、`SemanticExecutor`、`RawQueryAuthorizer`、DuckDB 全部类）放在 `heirloom-server`。

> **ADR-040 对应**：ADR-040 要求 `QueryRouter`、`RouteDecision`、`QueryRequest`、`QueryMode` 在 `heirloom-core`。本设计遵循此原则——纯类型和接口进 core，有 Spring/JDBC 依赖的实现类留 server。

---

## 5. API 端点

### 5.1 统一查询

`POST /v1/query` — QueryController 改造为接收 `QueryRequest` 并委托给 QueryRouter。

```json
// SEMANTIC
{"mode":"semantic","payload":{"type":"Customer","filter":{"tier":{"$eq":"Gold"}}}}

// RAW
{"mode":"raw","rawTable":"prod.pg.public.orders","rawSql":"SELECT status,SUM(amount) FROM {table} GROUP BY status"}

// HYBRID
{"mode":"hybrid","resource":{"type":"Customer","rid":"customer-123"},"drillDown":{"rawTable":"prod.pg.public.orders","rawSql":"SELECT * FROM {table} WHERE customer_id = :customer_id"}}
```

### 5.2 DuckDB 管理

| 端点 | 方法 | 功能 |
|------|:---:|------|
| `/v1/duckdb/sync/{tableFQN}` | POST | 手动触发同步 |
| `/v1/duckdb/tables` | GET | 列出已缓存的 raw 表 |
| `/v1/duckdb/tables/{tableFQN}/freshness` | GET | 查询 freshness 状态 |

---

## 6. 配置

```yaml
heirloom:
  duckdb:
    url: jdbc:duckdb:data/heirloom_raw.db
    freshness:
      ttl-minutes: 5
    sync:
      batch-size: 10000
      max-rows: 5000000
      sample-threshold: 1000000
      sample-rate: 0.1
```

DuckDB 可通过 `url` 配置切换到 `:memory:` 模式（测试用）。

---

## 7. 测试策略

| 层级 | 范围 | 工具 |
|------|------|------|
| 单元测试 | QueryRouter.decide() 路由逻辑、RawQueryAuthorizer DDL 拦截、FreshnessChecker TTL | JUnit 5 + Mockito |
| DuckDB 测试 | DuckDbRawStore 表创建/查询（`:memory:` 模式） | JUnit 5（无需 Testcontainers） |
| 集成测试 | DuckDbSyncService PG→DuckDB 端到端、POST /v1/query 三种模式 | Testcontainers + PostgreSQL |

DuckDB `:memory:` 模式优势：无需容器，完整 SQL 引擎，测试极快。

---

## 8. 实施计划

### 任务清单（11 tasks）

| Task | 内容 | 依赖 |
|------|------|:---:|
| 5.0 | 添加 `duckdb_jdbc:1.2.0` 依赖 + 配置 | — |
| 5.1 | `DuckDbRawStore` — 嵌入式 DuckDB（内存模式测试） | 5.0 |
| 5.2 | `DuckDbSyncService` — PG→DuckDB 同步 + Appender | 5.1 |
| 5.3 | `FreshnessChecker` — 基于 column_profiles 的过期判断 | 5.1 |
| 5.4 | `QueryMode` + `QueryRequest` + `RouteDecision` + record 定义 | — |
| 5.5 | `RawQueryAuthorizer` — 白名单 + DDL/DML 拦截 | — |
| 5.6 | `SemanticExecutor` — 从 QueryController 提取现有逻辑 | — |
| 5.7 | `QueryRouter` — 路由决策 + 三种模式执行 | 5.1–5.6 |
| 5.8 | `DuckDbResource` — `/v1/duckdb/*` 端点 | 5.1–5.3 |
| 5.9 | `QueryController` 改造 — `/v1/query` 统一入口 | 5.7 |
| 5.10 | 集成测试 + 端到端验证 | 5.9 |

### 工期：2.5–3.5 周

| 阶段 | Tasks | 周数 |
|------|-------|:---:|
| DuckDB 存储层 | 5.0–5.3 | 1 周 |
| QueryRouter 路由层 | 5.4–5.7 | 1 周 |
| API + 集成 | 5.8–5.10 | 0.5–1 周 |

### 测试预估

| 新增单元测试 | 新增集成测试 | 累计 |
|:---:|:---:|:---:|
| +25 | +5 | ~350 |

---

## 9. ADR 关系

| ADR | 状态 | 说明 |
|-----|------|------|
| ADR-037 | Proposed → 纳入本设计 | DuckDB raw store，调整：同步范围收窄、freshness 复用 column_profiles |
| ADR-040 | Proposed → 纳入本设计 | Query Router，调整：DuckDB 放在 server 内部 |
| ADR-043 | Proposed → 部分纳入 | DSL 扩展仅做 REST API，SDK/Workshop 推迟 |
| ADR-044 | Proposed → 纳入本设计 | Freshness 核心：复用 Phase 2 Profiling 数据 |
| ADR-046 | Accepted | Phase 5 范围定义 |
