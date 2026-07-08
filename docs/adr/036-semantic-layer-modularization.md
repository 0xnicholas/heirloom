# ADR-036: Semantic Layer 模块化与 Discovery Connector 扩展机制

## 状态

Proposed

## 日期

2026-07-08

## 上下文

Heirloom 项目正从单一 Spring Boot 应用（`heirloom-server`）向平台型分层架构演进。当前单体中混合了语义核心（Schema Registry、Action/Capability 模型）、数据源发现（Discovery）、知识库（Knowledge）、CDC、审计（Audit）等多种能力。随着定位扩展，我们需要回答两个问题：

1. **Semantic Layer 的边界在哪里？** 它应该是一个可被独立理解、复用和扩展的模块，而不是与具体服务实现纠缠在一起。
2. **数据源发现等能力如何接入？** 如果每个数据源都需要改动核心代码，核心将迅速膨胀并产生反向依赖。

参考 ADR-001（语义中枢）和 ADR-010（平台架构），Semantic Layer 是连接数据世界与业务世界的翻译器。为了保持这一层的深度和稳定性，必须把它从具体实现（REST 服务壳、Connector 实现）中剥离出来。

## 决策

**将 Heirloom 项目主体封装为 `heirloom-core` 语义核心模块，并通过 Maven 模块边界 + Spring Boot 自动配置接入 Discovery Connector 等扩展。**

> 注：本 ADR 中的“SPI”指 `heirloom-core` 定义的扩展接口契约，而非传统 JDK `ServiceLoader` 机制。接口本身不依赖 Spring Boot；运行时注册由 `heirloom-server` 或 Connector 的 Spring Boot 自动配置完成，与现有技术栈一致。

### 模块边界

| 模块 | 职责 | 约束 |
|------|------|------|
| `heirloom-core` | 语义模型、校验规则、Query Router SPI、扩展 SPI | 不依赖 Spring Boot；不依赖任何 Connector |
| `heirloom-connector-postgres` | PostgreSQL 数据源发现实现 | 不暴露 REST；不直接持久化 ResourceType |
| `heirloom-server` | 组装所有模块，暴露 REST/GraphQL，处理事务 | 不包含核心语义规则或具体提取逻辑 |

### Discovery SPI

`heirloom-core` 暴露以下最小接口：

```java
public interface SchemaExtractor {
    String sourceType();
    void prepare(DiscoveryConfig config);
    boolean testConnection();
    RawSchema extract();
    void close();
    Set<ExtractorCapability> capabilities();
}

public interface SchemaExtractorRegistry {
    void register(String sourceType, Supplier<SchemaExtractor> factory);
    Optional<SchemaExtractor> create(String sourceType);
    Set<String> supportedSourceTypes();
}
```

- `DiscoveryConfig` 在核心中仅为通用 record（`sourceType` + `connectionConfig` JSON），具体连接参数由 Connector 自行解析。
- 每个发现请求创建独立的 extractor 实例，避免单例状态竞争。
- `SchemaExtractorRegistry` 接口定义于 `heirloom-core`，其实现与运行时注册通过 Spring Boot 自动配置完成，以 prototype Bean 形式提供 extractor 工厂。

### 迁移策略

本阶段只迁移 PostgreSQL Connector 作为 SPI 边界验证。MySQL Connector 暂留 `heirloom-server`，待接口稳定后再迁出。

## 后果

**积极**：
- 编译期保证核心与扩展之间无反向依赖，边界清晰。
- `heirloom-core` 可作为纯 Java 库被非 Spring 消费者复用。
- 新增数据源只需新增 Connector 模块，不改核心。
- 为后续 Knowledge、CDC、Audit 等扩展提供统一模式。

**消极**：
- 引入 Maven 多模块构建，增加构建复杂度。
- 包迁移会破坏部分 import 路径，需要一轮回归测试。
- SPI 设计过早抽象风险：如果边界画错，后续重构成本更高。

## 备选方案

### 方案 A：保持单体，仅做包内部分包

在 `heirloom-server` 内部划分 `core.*` 和 `ext.*` 包，不拆 Maven 模块。

**放弃理由**：包级约定无法被编译器强制，容易随时间腐化。Maven 模块的依赖方向约束是更可靠的边界。

### 方案 B：核心库独立仓库

`heirloom-core` 单独仓库发布到 Maven 仓库，Connector 和服务各自独立仓库。

**放弃理由**：当前阶段过度设计，版本同步和 CI 协调成本高于收益。单仓多模块足以满足边界需求。

### 方案 C：一开始就迁移所有 Connector（Postgres + MySQL）

本阶段同时把 MySQL Connector 迁出。

**放弃理由**：增加首次迁移范围，延长验证周期。优先用 PostgreSQL 验证 SPI 稳定后再扩展，风险更低。

## 相关 ADR

- [ADR-001](./001-semantic-core-as-hub.md) — 语义中枢作为架构核心
- [ADR-018](./018-discovery-as-entity.md) — Discovery Engine 作为平台 Entity
- [ADR-019](./019-two-phase-discovery.md) — 两阶段发现——提取→推断
- [ADR-037](./037-duckdb-raw-store.md) — DuckDB 作为 raw 数据分析存储层
