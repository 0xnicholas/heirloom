# Heirloom Semantic Layer 核心模块化设计

> 状态：设计稿 v1.0  
> 目标：将 Heirloom 项目主体封装为可复用的 Semantic Layer 核心模块，并优先将 Discovery Connector 框架抽取为首个扩展验证边界。

---

## 1. 背景与目标

Heirloom 当前是一个单体 Spring Boot 应用（`heirloom-server`），内部混合了语义核心、数据源发现、知识库、CDC、审计等多种能力。随着项目定位扩展为“平台型分层架构中的 Semantic Layer”，需要将最小语义能力沉淀为独立模块，使 Discovery、Knowledge、CDC、Audit 等能力能够以 Java SPI / 库扩展的形式接入。

### 1.1 设计目标

- **清晰分层**：Semantic Layer 作为中间层，上接消费层（Agent / BI / 应用），下接数据源层。
- **模块隔离**：核心模块只包含最小语义能力，扩展通过 SPI 接入，编译期保证无反向依赖。
- **可验证边界**：优先抽取 Discovery Connector（PostgreSQL）作为首个扩展，验证接口稳定性。
- **渐进迁移**：不一次性大拆，而是分阶段建立模块、迁移代码、运行测试。

### 1.2 非目标

- 不引入微服务或服务间通信。
- 不立即拆分 Knowledge、CDC、Audit，仅定义 Discovery 作为试点。
- 不改变现有对外 REST/GraphQL 契约。

---

## 2. 架构定位

```
┌─────────────────────────────────────────────┐
│            消费层（Consumers）               │
│   Agent SDK  /  MCP Server  /  Workshop     │
│   REST API  /  GraphQL  /  Website          │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│       Semantic Layer 核心（heirloom-core）   │
│  Schema Registry  /  Resource & Type 模型   │
│  Role & Capability  /  Action Pipeline 接口 │
│  Query DSL  /  Mapping Rule  /  Discovery SPI│
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│            扩展层（Extensions）              │
│  Connector-Postgres  /  Connector-MySQL     │
│  Knowledge Base  /  CDC  /  Audit           │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│            数据源层（Data Sources）          │
│   PostgreSQL  /  MySQL  /  REST API         │
└─────────────────────────────────────────────┘
```

---

## 3. 模块结构

```
heirloom/
├── heirloom-core/                      # 新增：语义核心库
│   ├── src/main/java/com/heirloom/core/
│   │   ├── schema/                     # ResourceType, Field, StateMachine, Proposal
│   │   ├── security/                   # Role, Capability, Action 接口与流水线
│   │   ├── query/                      # SemanticQuery, QueryParser 接口
│   │   ├── mapping/                    # MappingRule 契约
│   │   ├── discovery/                  # Discovery SPI + 元数据模型
│   │   │   ├── spi/
│   │   │   │   ├── SchemaExtractor.java
│   │   │   │   ├── DiscoveryConfig.java
│   │   │   │   └── ExtractorCapability.java
│   │   │   ├── DiscoverySource.java
│   │   │   └── DiscoveryReport.java
│   │   ├── entity/                     # HeirloomEntity, FQN 契约
│   │   └── event/                      # 核心事件契约（ChangeEvent 等）
│   └── pom.xml                         # 纯 Java，无 Spring Boot
│
├── heirloom-connector-postgres/        # 新增：PG 发现扩展
│   ├── src/main/java/com/heirloom/connector/postgres/
│   │   └── PostgresSchemaExtractor.java
│   └── pom.xml                         # 依赖 heirloom-core + HikariCP + PG driver + Spring Boot（provided，仅自动配置用）
│
├── heirloom-connector-mysql/           # 预留：MySQL 发现扩展（本阶段不迁移）
│
├── heirloom-server/                    # 现有，改为服务壳
│   ├── src/main/java/com/heirloom/server/
│   │   ├── config/SchemaExtractorRegistration.java
│   │   ├── web/DiscoveryResource.java
│   │   └── service/DiscoveryOrchestratorImpl.java
│   └── pom.xml                         # 依赖 core + connector-postgres
│
├── heirloom-sdk/                       # 外部消费者，不变
├── heirloom-mcp/
├── website/
└── workshop/
```

### 3.1 各模块职责

| 模块 | 职责 | 禁止 |
|------|------|------|
| `heirloom-core` | 定义语义模型、校验规则、扩展 SPI（本阶段先定义 Discovery SPI，其他 SPI 后续补充） | 依赖 Spring Boot；依赖任何 Connector |
| `heirloom-connector-postgres` | 实现 `SchemaExtractor` 接口，解析 PG 连接配置；可用 Spring Boot 自动配置注册 Bean | 直接暴露 REST；直接操作 `ResourceType` 持久化 |
| `heirloom-server` | 组装所有模块，暴露 REST/GraphQL，处理事务和持久化；本阶段保留 MySQL Connector | 包含核心语义规则或具体提取逻辑 |

### 3.2 依赖方向

```
heirloom-sdk ──► heirloom-server
heirloom-connector-postgres ──► heirloom-core
heirloom-server ──► heirloom-core + heirloom-connector-postgres
```

**不允许反向依赖。**

**本阶段范围**：仅迁移 PostgreSQL Connector 验证 SPI。MySQL Connector 留在 `heirloom-server` 中，待 SPI 稳定后再迁出。

---

## 4. 核心模块接口设计

### 4.1 Discovery SPI

`heirloom-core` 只暴露以下最小接口：

```java
package com.heirloom.core.discovery.spi;

/**
 * 数据源 schema 提取器。
 *
 * 生命周期（由 orchestrator 保证）：
 * 1. prepare(config)   — 初始化连接池、解析并保存配置
 * 2. testConnection()  — 验证连通性（依赖 prepare 设置的内部状态）
 * 3. extract()         — 执行提取，复用 prepare 时保存的配置和连接
 * 4. close()           — 释放连接池等资源
 *
 * 线程安全：每个发现请求应使用独立的 extractor 实例（Prototype Bean 或由 orchestrator new 出来），
 * 避免单例状态下并发请求竞争连接池。
 */
public interface SchemaExtractor {
    String sourceType();
    void prepare(DiscoveryConfig config);
    boolean testConnection();
    RawSchema extract();
    void close();
    Set<ExtractorCapability> capabilities();
}

public interface SchemaExtractorRegistry {
    /**
     * 注册一个 extractor 工厂。
     * 每个发现请求都会通过 factory 创建新实例，避免单例状态竞争。
     */
    void register(String sourceType, Supplier<SchemaExtractor> factory);

    /**
     * 创建一个新的 extractor 实例。调用方负责在用完之后调用 close()。
     */
    Optional<SchemaExtractor> create(String sourceType);

    Set<String> supportedSourceTypes();
}

/**
 * 核心只保存原始 JSON 配置和 sourceType，不解析具体字段。
 * Connector 自行将 connectionConfig 反序列化为自己理解的配置对象。
 */
public record DiscoveryConfig(String sourceType, String connectionConfig) {}

/**
 * 发现过程中的可恢复错误。Connector 将底层异常包装为此异常抛出，
 * orchestrator 捕获后写入 DiscoveryReport.status = FAILED。
 */
public class DiscoveryException extends RuntimeException {
    public DiscoveryException(String message) { super(message); }
    public DiscoveryException(String message, Throwable cause) { super(message, cause); }
}
```

**生命周期说明**：
- 调用顺序：`prepare()` → `testConnection()` → `extract()` → `close()`。
- `testConnection()` 依赖 `prepare()` 设置的内部状态。
- 每个发现请求使用独立实例；orchestrator 负责在请求结束时调用 `close()` 释放资源。
- 实现类应保证 `prepare()` / `close()` 可重复调用（用于重试场景），但同一实例不应对并发调用开放。

### 4.1.1 原始 Schema 数据契约

Connector 与核心之间的主要数据交换格式：

```java
package com.heirloom.core.discovery.model;

public record RawSchema(
    String sourceName,              // 数据源标识，如 "host:database"
    String sourceType,              // "postgresql"
    List<RawTable> tables,          // 表列表
    String hash                     // schema 内容哈希，用于变更检测
) {}

public record RawTable(
    String schemaName,              // 数据库 schema，如 "public"
    String tableName,               // 表名
    String comment,                 // 表注释（可选）
    List<RawColumn> columns,
    List<RawConstraint> constraints,
    String hash                     // 表内容哈希
) {}

public record RawColumn(
    String columnName,
    String dataType,                // 原始数据类型，如 "integer", "character varying"
    boolean nullable,
    String comment,
    String defaultValue
) {}

public enum ConstraintType { PRIMARY_KEY, FOREIGN_KEY, UNIQUE }

public record RawConstraint(
    ConstraintType type,
    List<String> columns,           // 本表涉及列
    String targetTable,             // FK 目标表（仅 FK）
    List<String> targetColumns,     // FK 目标列（仅 FK）
    String deleteRule,              // FK 删除规则（仅 FK）
    String updateRule               // FK 更新规则（仅 FK）
) {}
```

核心不解释原始数据类型的语义，只将其作为输入交给 `InferencePipeline`。

**关系推断**：表间关系由 `RawTable.constraints` 中的外键约束推导，`RawSchema` 不单独携带关系列表。

### 4.2 元数据契约（核心拥有）

```java
package com.heirloom.core.discovery;

public class DiscoverySource {
    private String fullyQualifiedName;   // 如 "prod.pg.customers_db"
    private String sourceType;           // "postgresql"
    private String connectionConfig;     // JSON，核心不解析
    private String environment;
    private String status;
}

public class DiscoveryReport {
    private String status;               // SUCCESS / FAILED
    private int tablesScanned;
    private int proposalsGenerated;
    private int proposalsRegistered;
}
```

### 4.3 发现流程契约

核心定义编排接口，但不实现具体发现流程：

```java
package com.heirloom.core.discovery;

public interface DiscoveryOrchestrator {
    DiscoveryReport runDiscovery(DiscoverySource source);
}
```

`DiscoverySource` → `DiscoveryConfig` 映射：

```java
DiscoveryConfig config = new DiscoveryConfig(
    source.getSourceType(),
    source.getConnectionConfig()   // 原始 JSON，由 connector 自行解析
);
```

`heirloom-server` 提供实现，负责：

1. 从 `SchemaExtractorRegistry` 拿到对应 extractor（orchestrator 负责创建新实例）
2. 调用 `extractor.prepare(config)`、`testConnection()`、`extract()`，最后 `close()`
3. 把 `RawSchema` 交给 `InferencePipeline`
4. 为每个表创建 `TableEntity`，为外键创建 `LineageEntity`
5. 检测并标记已不存在的 stale 表
6. 注册/提案 `ResourceType`
7. 持久化 `DiscoveryReport`

### 4.4 Connector 注册方式

Connector 通过 Spring Boot 自动配置注册：

```java
package com.heirloom.connector.postgres;

@Configuration
@ConditionalOnClass(SchemaExtractorRegistry.class)
public class PostgresConnectorAutoConfiguration {
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public SchemaExtractor postgresSchemaExtractor() {
        return new PostgresSchemaExtractor();
    }
}
```

`heirloom-server` 启动时收集所有 `SchemaExtractor` Bean（应为 prototype 作用域），将其包装为 `Supplier` 注入 `SchemaExtractorRegistry`。

### 4.5 不进入公共 SPI 的能力

- `InferencePipeline` —— 属于 `heirloom-server` 内部实现，核心不暴露，Connector 不可见
- `ResourceType` 持久化接口——由 `heirloom-server` 内部使用
- `ActionPipeline` 内部步骤——只暴露 `ActionService` 高级接口
- GraphQL / REST 序列化——属于服务壳

---

## 5. 数据流与调用链

```
用户 / SDK
    │
    ▼
POST /v1/discovery/sources/{fqn}/run
    │
    ▼
┌─────────────────────────────────────┐
│  heirloom-server                    │
│  DiscoveryResource                  │
│  DiscoveryOrchestratorImpl          │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  heirloom-core                      │
│  SchemaExtractorRegistry.create()   │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  heirloom-connector-postgres        │
│  PostgresSchemaExtractor.extract()  │
│  连接外部 PG → 读取 information_schema│
└──────────────┬──────────────────────┘
               │
               ▼
         RawSchema
               │
               ▼
┌─────────────────────────────────────┐
│  heirloom-server                    │
│  InferencePipeline.infer()          │
│  注册 ResourceType / 生成 Proposal  │
│  持久化 DiscoveryReport             │
└─────────────────────────────────────┘
```

### 5.1 关键流程说明

1. **请求入口**：只有 `heirloom-server` 暴露 REST，Connector 和核心均不暴露 HTTP。
2. **Extractor 选择**：`DiscoveryOrchestratorImpl` 根据 `DiscoverySource.sourceType` 从 `SchemaExtractorRegistry` 创建新的 extractor 实例。
3. **外部连接**：Connector 自行创建连接池、执行 SQL、处理异常。
4. **结果转换**：Connector 返回 `RawSchema`，核心不感知具体 SQL。
5. **后续推理**：`InferencePipeline` 在 `heirloom-server` 中运行，生成语义类型提案。
6. **持久化**：`DiscoveryReport` 和 `ResourceType` 写回数据库，由服务层负责事务。

### 5.2 错误处理边界

| 错误类型 | 处理位置 | 行为 |
|---------|---------|------|
| 找不到对应 sourceType 的 Extractor | `DiscoveryOrchestratorImpl` | 返回 `FAILED`，报告 unsupported source type |
| 连接失败 / 认证错误 | Connector 内部 | 抛出 `DiscoveryException`，核心转 `FAILED` |
| SQL 执行失败 | Connector 内部 | 包装为 `DiscoveryException`，不泄漏到核心 |
| 推理失败 | `InferencePipeline` | 记录日志，单个表失败不影响其他表 |
| 持久化失败 | `heirloom-server` | 事务回滚，报告 `FAILED` |

---

## 6. 分步迁移计划

### 第 1 步：创建 `heirloom-core` 模块

目标：把 Discovery SPI 和元数据契约迁出，核心能独立编译。本阶段核心仅承载 Discovery 相关 SPI，security/query/mapping 等包可预留给后续扩展。

1. 新建 `heirloom-core/pom.xml`
   - `groupId=com.heirloom`，`artifactId=heirloom-core`
   - 依赖：Jakarta Persistence API、Jackson、SLF4J
   - **不依赖 Spring Boot**

2. 迁移以下类到 `heirloom-core`：
   - `com.heirloom.discovery.extractor.SchemaExtractor` → `com.heirloom.core.discovery.spi.SchemaExtractor`
   - `com.heirloom.discovery.model.*`（`RawSchema`, `RawTable`, `RawColumn`, `RawConstraint`）
   - `com.heirloom.discovery.domain.DiscoverySource` → `com.heirloom.core.discovery.DiscoverySource`
   - `com.heirloom.discovery.domain.DiscoveryReport` → `com.heirloom.core.discovery.DiscoveryReport`
   - `com.heirloom.discovery.extractor.ExtractorCapability` → `com.heirloom.core.discovery.spi.ExtractorCapability`
   - 新增 `com.heirloom.core.discovery.spi.SchemaExtractorRegistry` 接口
   - 新增 `com.heirloom.core.discovery.spi.DiscoveryConfig`（改为通用 record：`String sourceType, String connectionConfig`）

3. 在 `heirloom-server` 中保留：
   - `DiscoveryService`（后续改名为 `DiscoveryOrchestratorImpl`）
   - `DiscoveryResource`
   - `InferencePipeline`
   - 所有 Repository 和 Entity 持久化

### 第 2 步：创建 `heirloom-connector-postgres`

目标：把 PostgreSQL Connector 迁出，`heirloom-server` 不再包含 PG 提取逻辑。MySQL Connector 留在 `heirloom-server` 中，待 SPI 稳定后再迁。

1. 新建 `heirloom-connector-postgres/pom.xml`
   - 依赖 `heirloom-core`
   - 依赖 HikariCP、PostgreSQL JDBC driver
   - 以 `provided` 方式依赖 Spring Boot（仅用于自动配置类）

2. 迁移 `PostgresSchemaExtractor` 到新模块
   - 包名改为 `com.heirloom.connector.postgres`
   - 保持 schema 提取逻辑不变，但 `extract()` 改为无参、复用 `prepare()` 保存的配置
   - 在模块内部新增 PG 专用配置解析（如 `PostgresConnectionConfig`），从 `DiscoveryConfig.connectionConfig` JSON 反序列化 `host/port/database/username/password/schema`

3. 添加 Spring Boot 自动配置：
   - `PostgresConnectorAutoConfiguration` 提供 prototype 作用域的 `SchemaExtractor` Bean

### 第 3 步：改造 `heirloom-server` 为组装器

1. `heirloom-server/pom.xml` 添加依赖：
   - `heirloom-core`
   - `heirloom-connector-postgres`
   - MySQL 相关代码暂留在 `heirloom-server`，不新增 `heirloom-connector-mysql` 依赖

2. 引入 `SchemaExtractorRegistry` 实现：

   ```java
   @Component
   public class SpringSchemaExtractorRegistry implements SchemaExtractorRegistry {
       private final Map<String, ObjectProvider<SchemaExtractor>> providers = new ConcurrentHashMap<>();

       public SpringSchemaExtractorRegistry(Map<String, ObjectProvider<SchemaExtractor>> providers) {
           this.providers.putAll(providers);
       }

       @Override
       public void register(String sourceType, Supplier<SchemaExtractor> factory) {
           providers.put(sourceType, new SimpleObjectProvider<>(factory));
       }

       @Override
       public Optional<SchemaExtractor> create(String sourceType) {
           ObjectProvider<SchemaExtractor> provider = providers.get(sourceType);
           return provider != null ? Optional.of(provider.getObject()) : Optional.empty();
       }

       @Override
       public Set<String> supportedSourceTypes() {
           return Set.copyOf(providers.keySet());
       }
   }
   ```

   **关键**：`ObjectProvider.getObject()` 对 prototype Bean 每次返回新实例，满足“每个发现请求独立 extractor”的要求。

3. `DiscoveryService` 改名为 `DiscoveryOrchestratorImpl`，从 registry 创建 extractor 实例，并在 finally 块中调用 `close()`。

### 第 4 步：运行测试并修复

- 运行现有 `DiscoveryE2ETest` 和 Unit Tests
- 修复包引用、依赖注入问题

### 第 5 步：后续扩展（Knowledge / CDC / Audit）

按相同模式，每次抽取一个扩展：

1. 在 `heirloom-core` 定义 SPI 和元数据契约
2. 新建 `heirloom-ext-*` 模块实现 SPI
3. `heirloom-server` 注册并编排

---

## 7. 测试策略

### 7.1 核心模块测试（`heirloom-core`）

- **单元测试**：`SchemaExtractorRegistry` 的注册/解析逻辑
- **契约测试**：`DiscoveryConfig` JSON 解析、`RawSchema` 构建
- **ArchUnit 测试**：确保 `heirloom-core` 不依赖 Spring、不依赖任何 Connector

```java
@ArchTest
static final ArchRule core_should_not_depend_on_spring =
    noClasses().that().resideInAPackage("com.heirloom.core..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework..");
```

### 7.2 Connector 测试（`heirloom-connector-postgres`）

- **单元测试**：`PostgresSchemaExtractor` 对 `ResultSet` 的解析
- **集成测试**：使用 Testcontainers 启动 PostgreSQL，执行完整发现流程
- **接口契约测试**：确保 `PostgresSchemaExtractor` 正确实现 `SchemaExtractor`

### 7.3 服务层测试（`heirloom-server`）

- **集成测试**：`DiscoveryOrchestratorImpl` + In-Memory Registry + Fake Extractor
- **E2E 测试**：`DiscoveryE2ETest` 保留，验证端到端流程
- **ArchUnit 测试**：确保 `heirloom-server` 不直接引用 Connector 具体类，只通过 SPI 调用

### 7.4 关键测试原则

| 原则 | 说明 |
|------|------|
| 不跨模块测实现 | 核心测试不启动 PG；Connector 测试不启动 Spring |
| Fake 优先 | `heirloom-server` 的单元测试用 `FakeSchemaExtractor`，而非真实 PG |
| 边界测试 | 每个模块用 ArchUnit 保证依赖方向 |

### 7.5 测试迁移清单

| 现有测试 | 新位置 |
|---------|--------|
| `DiscoveryE2ETest` | `heirloom-server`（不变） |
| `PostgresSchemaExtractor` 相关单元测试 | `heirloom-connector-postgres` |
| 新增 `SchemaExtractorRegistryTest` | `heirloom-core` |
| 新增 ArchUnit 测试 | 每个模块各一个 |

---

## 8. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 包名变更破坏历史 Commit/Blame | 使用 `git mv` 保留历史 |
| 测试环境依赖 Testcontainers | 第 1 步只迁接口，不改实现逻辑 |
| 循环依赖 | 严格执行核心不依赖 Spring Boot / Connector |
| 扩展点过早抽象 | 以 Discovery 为试点，接口稳定后再扩展 |
| 现有 PR/分支冲突 | 选择低活跃期执行，或提前沟通 |

---

## 9. 后续展望

本设计完成后，可依次抽取：

- **Knowledge Base**：核心暴露 `KnowledgeSource` 元数据契约 + `KnowledgeImporter` SPI
- **CDC**：核心暴露 `ChangeEvent` 契约 + `ChangeEventPublisher` SPI
- **Audit**：核心暴露 `AuditEvent` 写入接口，审计看板作为扩展

最终形成 `heirloom-core` 为稳定语义内核，各种 Connector 和扩展按需组合的平台架构。

---

## 10. 决策记录

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-07-08 | 采用 Maven 多模块单仓 | 协调成本低，边界由编译器保证 |
| 2026-07-08 | 核心模块不依赖 Spring Boot | 保持纯语义层，可被非 Spring 消费者复用 |
| 2026-07-08 | 优先抽取 Discovery Connector | 接口简单，现有实现成熟，适合验证边界 |
| 2026-07-08 | Connector 通过 Spring Boot 自动配置注册 | 与现有技术栈一致，低侵入 |
