# Phase 5: DuckDB Raw Store + Query Router — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add DuckDB embedded analytical store and unified Query Router to Heirloom, enabling raw SQL queries and hybrid semantic+raw queries against discovered PostgreSQL tables.

**Architecture:** DuckDB embedded via JDBC in heirloom-server. QueryRouter (contracts in heirloom-core, impl in server) routes queries to semantic layer or DuckDB based on mode. Freshness reuses Phase 2 column_profiles. Sync uses atomic CREATE tmp → DROP old → RENAME pattern with table-level locks.

**Tech Stack:** Java 21, Spring Boot 3.5.4, DuckDB JDBC 1.2.0, PostgreSQL, existing heirloom-core/heirloom-server modules

**Prerequisites:** This plan builds on Phase 0-4 (already committed to `feature/metadata-foundation`). The following classes already exist from prior phases:
- `heirloom-core` module + `EntityRegistry` + `SemanticQuery` + `SqlGenerator` + `HeirloomEntity`
- `heirloom-server` — `ColumnDef`/`ColumnDefParser`, `ColumnProfileEntity`/`ColumnProfileJpaRepository`, `TableEntity`/`TableRepository`, `ClassificationEntity`/`ClassificationJpaRepository`, `UnauthorizedException`, `GlobalExceptionHandler`

**Spec:** `docs/superpowers/specs/2026-07-16-duckdb-query-router.md`

---

## 文件结构

```
heirloom-core/src/main/java/com/heirloom/core/query/
├── QueryMode.java                  # NEW: enum SEMANTIC, RAW, HYBRID, AUTO
├── QueryRequest.java               # NEW: unified query request record
├── QueryResult.java                # NEW: unified query result record
├── RouteDecision.java              # NEW: route decision record
├── RouteStep.java                  # NEW: hybrid multi-step record
├── ResourceRef.java                # NEW: HYBRID semantic reference
├── DrillDown.java                  # NEW: HYBRID raw drill-down
├── SemanticQuery.java              # existing (from Phase 0)
└── SqlGenerator.java               # existing interface

heirloom-server/src/main/java/com/heirloom/
├── duckdb/
│   ├── DuckDbRawStore.java         # NEW: DuckDB connection + SQL
│   ├── DuckDbSyncService.java      # NEW: PG → DuckDB sync + Appender
│   ├── DuckDbNaming.java           # NEW: FQN ↔ internal name mapping
│   ├── FreshnessChecker.java       # NEW: TTL-based freshness
│   └── web/
│       └── DuckDbResource.java     # NEW: /v1/duckdb/* REST
├── query/
│   ├── QueryRouter.java            # NEW: route decision + execution
│   ├── SemanticExecutor.java       # NEW: extract from QueryController
│   └── RawQueryAuthorizer.java     # NEW: security checks
└── web/
    └── QueryController.java        # MODIFY: unified /v1/query entry

heirloom-server/
├── pom.xml                         # MODIFY: + duckdb_jdbc dependency
├── src/main/resources/application.yml  # MODIFY: + heirloom.duckdb.* config
└── src/test/java/com/heirloom/
    ├── duckdb/
    │   ├── DuckDbRawStoreTest.java
    │   ├── DuckDbNamingTest.java
    │   ├── DuckDbSyncServiceTest.java
    │   ├── DuckDbSyncServiceIntegrationTest.java
    │   └── FreshnessCheckerTest.java
    ├── query/
    │   ├── QueryRouterTest.java
    │   └── RawQueryAuthorizerTest.java
    └── web/
        └── QueryControllerIntegrationTest.java
```

### Task 5.0: 添加 DuckDB 依赖 + 配置

**Files:**
- Modify: `heirloom-server/pom.xml`
- Modify: `heirloom-server/src/main/resources/application.yml`

- [x] **Step 1: 添加 duckdb_jdbc 依赖到 pom.xml**

```xml
<dependency>
    <groupId>org.duckdb</groupId>
    <artifactId>duckdb_jdbc</artifactId>
    <version>1.2.0</version>
</dependency>
```

- [x] **Step 2: 添加配置到 application.yml**

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

- [x] **Step 3: 编译验证**

```bash
./heirloom-server/mvnw compile -pl heirloom-server
```
Expected: BUILD SUCCESS.

- [x] **Step 4: Commit**

```bash
git add heirloom-server/pom.xml heirloom-server/src/main/resources/application.yml
git commit -m "feat: add duckdb_jdbc 1.2.0 dependency and heirloom.duckdb configuration"
```

---

### Task 5.1: DuckDbRawStore — 嵌入式 DuckDB 管理

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/duckdb/DuckDbRawStore.java`
- Test: `heirloom-server/src/test/java/com/heirloom/duckdb/DuckDbRawStoreTest.java`

- [x] **Step 1: 写测试（内存模式）**

```java
class DuckDbRawStoreTest {
    private DuckDbRawStore store;

    @BeforeEach
    void setUp() { store = new DuckDbRawStore("jdbc:duckdb::memory:"); }
    @AfterEach void tearDown() { store.close(); }

    @Test
    void shouldCreateTableAndQuery() {
        store.execute("CREATE TABLE test (id INTEGER, name VARCHAR)");
        store.execute("INSERT INTO test VALUES (1, 'foo')");
        var rows = store.query("SELECT * FROM test");
        assertEquals(1, rows.size());
        assertEquals("foo", rows.get(0).get("name"));
    }

    @Test
    void shouldReportTableExists() {
        store.execute("CREATE TABLE t1 (x INT)");
        assertTrue(store.tableExists("t1"));
        assertFalse(store.tableExists("t2"));
    }
}
```

- [x] **Step 2: 运行测试验证失败**

```bash
./heirloom-server/mvnw test -pl heirloom-server -Dtest="DuckDbRawStoreTest"
```
Expected: FAIL — DuckDbRawStore not yet implemented.

- [x] **Step 3: 实现 DuckDbRawStore**

```java
@Service
public class DuckDbRawStore implements AutoCloseable {

    private final Connection conn;

    public DuckDbRawStore(@Value("${heirloom.duckdb.url:jdbc:duckdb:data/heirloom_raw.db}") String url) {
        this.conn = DriverManager.getConnection(url);
    }

    public List<Map<String, Object>> query(String sql) {
        List<Map<String, Object>> results = new ArrayList<>();
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            var meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                results.add(row);
            }
        } catch (SQLException e) {
            throw new RuntimeException("DuckDB query failed", e);
        }
        return results;
    }

    public synchronized void execute(String sql) {
        try (var stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("DuckDB execute failed", e);
        }
    }

    public boolean tableExists(String tableName) {
        try {
            var rs = conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '" + tableName + "'");
            rs.next();
            return rs.getInt(1) > 0;
        } catch (SQLException e) { return false; }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
```

- [x] **Step 4: 运行测试验证通过**

```bash
./heirloom-server/mvnw test -pl heirloom-server -Dtest="DuckDbRawStoreTest"
```
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/duckdb/DuckDbRawStore.java
git add heirloom-server/src/test/java/com/heirloom/duckdb/DuckDbRawStoreTest.java
git commit -m "feat: implement DuckDbRawStore — embedded DuckDB JDBC with in-memory test support"
```

---

### Task 5.2: DuckDbNaming + DuckDbSyncService — PG → DuckDB 同步

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/duckdb/DuckDbNaming.java`
- Create: `heirloom-server/src/main/java/com/heirloom/duckdb/DuckDbSyncService.java`
- Test: `heirloom-server/src/test/java/com/heirloom/duckdb/DuckDbSyncServiceTest.java`

- [x] **Step 1: 先写 DuckDbNaming 测试**

```java
class DuckDbNamingTest {
    @Test void shouldConvertFQNToDuckDb() {
        assertEquals("_raw_prod_pg_public_orders", DuckDbNaming.toDuckDbName("prod.pg.public.orders"));
    }
    @Test void shouldConvertDuckDbToFQN() {
        assertEquals("prod.pg.public.orders", DuckDbNaming.fromDuckDbName("_raw_prod_pg_public_orders"));
    }
}
```

- [x] **Step 2: 运行测试验证失败** → FAIL（类不存在）
- [x] **Step 3: 实现 DuckDbNaming.java**（代码见上）→ 运行测试 → PASS
- [x] **Step 4: 实现 DuckDbSyncService**

核心逻辑（伪代码——实现时读现有 TableRepository/ColumnDefParser/DataSource）：

```java
@Service
public class DuckDbSyncService {
    private final DuckDbRawStore duckDb;
    private final DataSource sourceDataSource;
    private final TableRepository tableRepo;
    private final ColumnProfileRepository profileRepo;
    private final ConcurrentHashMap<String, Object> tableLocks = new ConcurrentHashMap<>();

    public SyncResult sync(String tableFQN) {
        Object lock = tableLocks.computeIfAbsent(tableFQN, k -> new Object());
        synchronized (lock) {
            try {
                TableEntity table = tableRepo.findByFQN(tableFQN).orElseThrow();
                List<ColumnDef> columns = ColumnDefParser.parse(table.getColumnsJson());
                String duckDbName = DuckDbNaming.toDuckDbName(tableFQN);
                String tmpName = duckDbName + "_tmp";

                String ddl = buildCreateTableSql(columns, tmpName);
                duckDb.execute("DROP TABLE IF EXISTS \"" + tmpName + "\"");
                duckDb.execute(ddl);

                long rowCount = appendFromPostgres(tableFQN, columns, tmpName);

                duckDb.execute("DROP TABLE IF EXISTS \"" + duckDbName + "\"");
                duckDb.execute("ALTER TABLE \"" + tmpName + "\" RENAME TO \"" + duckDbName + "\"");

                saveSyncTrace(tableFQN, rowCount);
                long duration = System.currentTimeMillis() - start;
                return new SyncResult(tableFQN, rowCount, duration);
            } finally {
                tableLocks.remove(tableFQN);
            }
        }
    }

    private void saveSyncTrace(String tableFQN, long rowCount) {
        var trace = new ColumnProfileEntity();
        trace.setTableFQN(tableFQN);
        trace.setColumnName("_row_count");
        trace.setProfiledAt(Instant.now());
        trace.setNullCount(rowCount);  // 复用存行数（已知局限，Phase 6 可拆表）
        profileRepo.create(trace);     // EntityRepository.create(), 不是 JPA save()
    }

    private long appendFromPostgres(String tableFQN, List<ColumnDef> columns, String targetTable) {
        // 1. 从源 PG 执行 SELECT * FROM tableFQN
        // 2. 如果 estimatedRowCount > sampleThreshold: 使用 TABLESAMPLE BERNOULLI(sampleRate*100)
        // 3. 使用 DuckDB Appender API 批量写入 targetTable
        // 参考 Spec §3.2 描述
    }

    private String buildCreateTableSql(List<ColumnDef> columns, String tableName) {
        // 遍历 ColumnDef: dataType → DuckDB 对应类型
        // varchar → VARCHAR, integer → INTEGER, timestamp → TIMESTAMP, etc.
        // 返回 "CREATE TABLE \"tableName\" (col1 TYPE, col2 TYPE, ...)"
    }
}
```

- [x] **Step 4: 写集成测试**

```java
@Testcontainers
class DuckDbSyncServiceIntegrationTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Test void shouldSyncTableFromPostgres() {
        // 建表 + 插入源 PG 数据
        // 注册到 metadata_tables
        // 调用 syncService.sync("test.public.users")
        // 验证 DuckDB 中表存在 + 行数正确
    }
}
```

- [x] **Step 5: Commit**

```bash
git add heirloom-server/src/main/java/com/heirloom/duckdb/
git add heirloom-server/src/test/java/com/heirloom/duckdb/
git commit -m "feat: implement DuckDbSyncService — PG→DuckDB sync with Appender, atomic rename, table-level locking"
```

---

### Task 5.3: FreshnessChecker

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/duckdb/FreshnessChecker.java`
- Test: `heirloom-server/src/test/java/com/heirloom/duckdb/FreshnessCheckerTest.java`

- [x] **Step 1: 写测试**

```java
class FreshnessCheckerTest {
    @Mock DuckDbRawStore duckDb;
    @Mock ColumnProfileJpaRepository profileRepo;
    @InjectMocks FreshnessChecker checker;

    @Test
    void shouldReturnNotFresh_WhenTableMissing() {
        when(duckDb.tableExists("_raw_public_orders")).thenReturn(false);
        assertFalse(checker.isFresh("public.orders"));
    }

    @Test
    void shouldReturnFresh_WhenProfileRecent() {
        when(duckDb.tableExists("_raw_public_orders")).thenReturn(true);
        var profile = new ColumnProfileEntity();
        profile.setProfiledAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(profileRepo.findByTableFQNAndColumnNameOrderByProfiledAtDesc("public.orders", "_row_count"))
            .thenReturn(List.of(profile));
        assertTrue(checker.isFresh("public.orders"));
    }
}
```

- [x] **Step 2: 实现 FreshnessChecker**

```java
@Component
public class FreshnessChecker {
    private final DuckDbRawStore duckDb;
    private final ColumnProfileJpaRepository profileRepo;

    @Value("${heirloom.duckdb.freshness.ttl-minutes:5}")
    private int ttlMinutes;

    public boolean isFresh(String tableFQN) {
        String duckDbName = DuckDbNaming.toDuckDbName(tableFQN);
        if (!duckDb.tableExists(duckDbName)) return false;
        var latest = profileRepo
            .findByTableFQNAndColumnNameOrderByProfiledAtDesc(tableFQN, "_row_count")
            .stream().findFirst();
        if (latest.isEmpty()) return false;
        return latest.get().getProfiledAt()
            .isAfter(Instant.now().minus(ttlMinutes, ChronoUnit.MINUTES));
    }

    public Instant lastSyncedAt(String tableFQN) { ... }
    public String ttlDescription() { return ttlMinutes + " minutes"; }
}
```

- [x] **Step 3: 测试 + Commit**

```bash
./heirloom-server/mvnw test -pl heirloom-server -Dtest="FreshnessCheckerTest"
git add -A
git commit -m "feat: implement FreshnessChecker with TTL-based freshness using column_profiles"
```

---

### Task 5.4: Query 契约类型 — heirloom-core

**Files:**
- Create: `heirloom-core/src/main/java/com/heirloom/core/query/QueryMode.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/query/QueryRequest.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/query/QueryResult.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/query/RouteDecision.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/query/RouteStep.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/query/ResourceRef.java`
- Create: `heirloom-core/src/main/java/com/heirloom/core/query/DrillDown.java`

All 7 records/enums plus QueryPayload. Full code from spec §4.2:

```java
// QueryMode.java
public enum QueryMode { SEMANTIC, RAW, HYBRID, AUTO }

// QueryPayload.java — NEW (missing from spec §4.2 definition, needed for compilation)
public record QueryPayload(
    String type,                    // ResourceType name
    Map<String, Object> filter,     // JSON DSL filter (existing format)
    List<String> fields             // requested fields (null = all)
) {}

// QueryRequest.java
public record QueryRequest(QueryMode mode, QueryPayload payload, String rawTable,
    String rawSql, ResourceRef resource, DrillDown drillDown) {}

// QueryResult.java
public record QueryResult(List<Map<String, Object>> rows, long totalCount, QueryMode resolvedMode,
    boolean fresh, Long executionTimeMs, Map<String, Object> meta, Map<String, Object> bindings) {}

// RouteDecision.java
public record RouteDecision(QueryMode mode, QueryPayload payload, String rawTable,
    String rawSql, ResourceRef resource, DrillDown drillDown, List<RouteStep> steps) {}

// RouteStep.java
public record RouteStep(String engine, QueryPayload payload, Map<String, Object> bindings) {}

// ResourceRef.java
public record ResourceRef(String type, String rid, List<String> fields) {}

// DrillDown.java
public record DrillDown(String rawTable, String rawSql) {}
```

Additional support types in heirloom-server:

```java
// SyncResult.java — heirloom-server/.../duckdb/SyncResult.java
public record SyncResult(String tableFQN, long rowCount, long durationMs) {}

// FreshnessStatus.java — heirloom-server/.../duckdb/FreshnessStatus.java
public record FreshnessStatus(String tableFQN, boolean fresh, Instant lastSyncedAt, String ttl) {}
```

- [x] **Step 1: 创建全部 9 个文件**（7 个 core 类型 + QueryPayload + 2 个 server 类型）
- [x] **Step 2: 编译 heirloom-core** `./heirloom-server/mvnw compile -pl heirloom-core`
- [x] **Step 3: Commit**

```bash
git add heirloom-core/
git commit -m "feat: define query contract types in heirloom-core (QueryMode, QueryRequest, QueryResult, RouteDecision, RouteStep, ResourceRef, DrillDown)"
```

---

### Task 5.5: RawQueryAuthorizer

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/query/RawQueryAuthorizer.java`
- Test: `heirloom-server/src/test/java/com/heirloom/query/RawQueryAuthorizerTest.java`

- [x] **Step 1: 写测试（TDD）**

```java
class RawQueryAuthorizerTest {
    @Mock TableRepository tableRepo;
    @InjectMocks RawQueryAuthorizer authorizer;

    @Test void shouldAllowRegisteredTableWithSelectAndLimit() {
        when(tableRepo.existsByFQN("public.orders")).thenReturn(true);
        assertDoesNotThrow(() -> authorizer.check("public.orders", "SELECT * FROM {table} LIMIT 10"));
    }

    @Test void shouldRejectUnregisteredTable() {
        when(tableRepo.findByFQN("public.secret")).thenReturn(Optional.empty());
        assertThrows(UnauthorizedException.class,
            () -> authorizer.check("public.secret", "SELECT * FROM {table} LIMIT 10"));
    }

    @Test void shouldRejectDDL() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertThrows(UnauthorizedException.class,
            () -> authorizer.check("public.orders", "DROP TABLE {table}"));
    }

    @Test void shouldRejectMultiStatement() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertThrows(UnauthorizedException.class,
            () -> authorizer.check("public.orders", "SELECT * FROM {table} LIMIT 10; DROP TABLE {table}"));
    }

    @Test void shouldRejectWithoutLimit() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertThrows(UnauthorizedException.class,
            () -> authorizer.check("public.orders", "SELECT * FROM {table}"));
    }

    @Test void shouldAllowWithCommentBeforeSelect() {
        when(tableRepo.findByFQN("public.orders")).thenReturn(Optional.of(mock(TableEntity.class)));
        assertDoesNotThrow(() -> authorizer.check("public.orders", "-- comment\nSELECT * FROM {table} LIMIT 10"));
    }
}
```

- [x] **Step 2: 实现 RawQueryAuthorizer**（代码见 Spec §4.5）

> **注意**: `TableRepository` 现有方法为 `findByFQN(fqn)` 返回 `Optional`。实现时用 `tableRepo.findByFQN(tableFQN).isPresent()` 替代 `existsByFQN()`。`UnauthorizedException` 现有构造函数为 `(String entityType, String operation, String message)`，抛异常时用 `new UnauthorizedException("raw_query", "SELECT", "...")`。
- [x] **Step 3: 测试 + Commit**

```bash
./heirloom-server/mvnw test -pl heirloom-server -Dtest="RawQueryAuthorizerTest"
git add -A
git commit -m "feat: implement RawQueryAuthorizer with white-list, DDL block, comment stripping, statement chaining defense"
```

---

### Task 5.6: SemanticExecutor — 提取现有逻辑

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/query/SemanticExecutor.java`
- Modify: `heirloom-server/src/main/java/com/heirloom/web/QueryController.java` — 提取逻辑，保留薄层

- [x] **Step 1: 读现有 QueryController.java** 理解当前逻辑
- [x] **Step 2: 创建 SemanticExecutor**，移入 QueryParser → PerspectiveEngine → SqlGenerator → JdbcTemplate 的执行链
- [x] **Step 3: 修改 QueryController** 委托给 SemanticExecutor
- [x] **Step 4: 运行现有测试确认不破坏**

```bash
./heirloom-server/mvnw test -Dtest='!*IntegrationTest,!*IT,!*E2E*'
```

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: extract SemanticExecutor from QueryController"
```

---

### Task 5.7: QueryRouter — 路由决策 + 执行

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/query/QueryRouter.java`
- Test: `heirloom-server/src/test/java/com/heirloom/query/QueryRouterTest.java`

- [x] **Step 1: 写测试**

```java
class QueryRouterTest {
    @Mock SemanticExecutor semantic;
    @Mock DuckDbRawStore duckDb;
    @Mock FreshnessChecker freshness;
    @Mock DuckDbSyncService sync;
    @Mock RawQueryAuthorizer authorizer;
    @InjectMocks QueryRouter router;

    @Test void shouldRouteToRaw_WhenModeIsRaw() {
        var req = new QueryRequest(QueryMode.RAW, null, "public.orders", "SELECT * FROM {table} LIMIT 10", null, null);
        when(freshness.isFresh("public.orders")).thenReturn(true);
        router.execute(req);
        verify(duckDb).query(anyString());
    }

    @Test void shouldRouteToSemantic_WhenPayloadHasType() {
        var payload = new QueryPayload("Customer", Map.of("tier", Map.of("$eq", "Gold")), null);
        var req = new QueryRequest(QueryMode.AUTO, payload, null, null, null, null);
        router.execute(req);
        verify(semantic).execute(payload);
    }

    @Test void shouldSyncBeforeQuery_WhenNotFresh() {
        var req = new QueryRequest(QueryMode.RAW, null, "public.orders", "SELECT * FROM {table} LIMIT 10", null, null);
        when(freshness.isFresh("public.orders")).thenReturn(false);
        router.execute(req);
        verify(sync).sync("public.orders");
        verify(duckDb).query(anyString());
    }
}
```

- [x] **Step 2: 实现 QueryRouter**（decide + executeRoute 逻辑，见 Spec §4.1-4.3）
- [x] **Step 3: 测试 + Commit**

---

### Task 5.8: DuckDbResource — REST 端点

**Files:**
- Create: `heirloom-server/src/main/java/com/heirloom/duckdb/web/DuckDbResource.java`

- [x] **Step 1: 实现三个端点**

```java
@RestController
@RequestMapping("/v1/duckdb")
public class DuckDbResource {
    @PostMapping("/sync/{tableFQN}")
    public SyncResult sync(@PathVariable String tableFQN) { ... }

    @GetMapping("/tables/{tableFQN}/freshness")
    public FreshnessStatus freshness(@PathVariable String tableFQN) { ... }

    @GetMapping("/tables")
    public List<String> tables() { ... }
}
```

- [x] **Step 2: 编译验证** `./heirloom-server/mvnw compile -pl heirloom-server`
- [x] **Step 3: 写 WebMvcTest**

```java
@WebMvcTest(DuckDbResource.class)
class DuckDbResourceTest {
    @Test void shouldTriggerSync() { ... }
    @Test void shouldReturnFreshness() { ... }
    @Test void shouldListTables() { ... }
}
```

- [x] **Step 4: 运行测试** `./heirloom-server/mvnw test -pl heirloom-server -Dtest="DuckDbResourceTest"`
- [x] **Step 5: Commit**

---

### Task 5.9: QueryController 改造 — 统一入口

**Files:**
- Modify: `heirloom-server/src/main/java/com/heirloom/web/QueryController.java`

- [x] **Step 1: 修改 POST /v1/query** 接收 `QueryRequest` 并委托给 `queryRouter.execute()`
- [x] **Step 2: 向后兼容** 现有 `{"type":"Customer","filter":...}` 格式通过 QueryRouter.auto → SEMANTIC 路径执行
- [x] **Step 3: 全量测试**

```bash
./heirloom-server/mvnw test -Dtest='!*IntegrationTest,!*IT,!*E2E*'
```

- [x] **Step 4: Commit**

---

### Task 5.10: 集成测试 + 端到端验证

**Files:**
- Create: `heirloom-server/src/test/java/com/heirloom/web/QueryControllerIntegrationTest.java`

- [x] **Step 1: 写集成测试** — 三种模式端到端

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
class QueryControllerIntegrationTest {
    @Test void shouldExecuteSemanticQuery() {
        // POST /v1/query with {"mode":"semantic","payload":{...}}
        // assert 200 + rows
    }
    @Test void shouldExecuteRawQuery() {
        // POST /v1/query with {"mode":"raw","rawTable":"...","rawSql":"SELECT * FROM {table} LIMIT 5"}
        // assert 200 + rows
    }
    @Test void shouldExecuteHybridQuery() {
        // POST /v1/query with {"mode":"hybrid","resource":{...},"drillDown":{...}}
        // assert 200 + rows
    }
    @Test void shouldRejectRawQueryWithoutLimit() {
        // POST /v1/query with rawSql without LIMIT
        // assert 403
    }
}
```

- [x] **Step 2: 全量测试** `./heirloom-server/mvnw test -Dtest='!*IntegrationTest,!*IT,!*E2E*'`
- [x] **Step 3: Phase 5 完成 Commit**

```bash
git add -A
git commit -m "chore: complete Phase 5 — DuckDB raw store + Query Router, integration tests"
```

---

## 总结

| Phase 5 | 新建 | 修改 | 测试新增 |
|---------|:---:|:---:|:---:|
| Tasks 5.0-5.10 | ~17 | 2 | +20 |
| **累计** | — | — | ~340 |
