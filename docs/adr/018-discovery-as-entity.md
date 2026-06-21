# ADR-018: Discovery Engine 作为平台 Entity

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Discovery Engine 的核心功能是扫描数据源 schema 并生成 ResourceTypeProposal。
有两种架构位置：
1. 作为独立脚本/工具——通过 CLI 调用，产出文件
2. 作为平台 Entity——DiscoverySource/DiscoveryReport 是标准 Entity，CRUD 走标准端点

方案 1 的问题是：无法通过平台 API 查询扫描历史、无法在 UI 中管理数据源配置、
无法定时调度、无法与其他平台功能（审计、授权）集成。

## 决策

**Discovery Engine 作为平台 Entity。** `DiscoverySource` 和 `DiscoveryReport`
实现 `HeirloomEntity`，走标准 `EntityResource`/`EntityRepository` 路径。

### DiscoverySource（对标 OM 的 IngestionPipeline）

```java
@Entity
public class DiscoverySource implements HeirloomEntity {
    private Long id;
    private String name;              // "postgres-prod-analytics"
    private String fullyQualifiedName; // "prod.postgres-analytics"
    private String sourceType;         // "postgresql"
    private String connectionConfig;   // JSON
    private String schedule;           // "manual" | cron
    private String status;             // ACTIVE | PAUSED | ERROR
}
```

### DiscoveryReport（对标 OM 的 PipelineRun 记录）

```java
@Entity
public class DiscoveryReport implements HeirloomEntity {
    private String sourceFQN;          // 关联 DiscoverySource
    private String status;             // RUNNING | SUCCESS | PARTIAL_SUCCESS | FAILED
    private Integer tablesScanned;
    private Integer proposalsGenerated;
    private Integer proposalsRegistered;
    private String contentHash;        // 增量检测
    private Long durationMs;
    private String errorSummary;       // JSON
    private Boolean partialSuccess;
}
```

### 触发扫描

DiscoveryResource 提供自定义端点（不在标准 CRUD 内）：
- `POST /v1/discovery/sources/{sourceFQN}/run` → 触发扫描

扫描逻辑（DiscoveryService.runDiscovery）是平台上的标准服务方法——可被 REST API、
定时调度、Event Log 事件触发。

## 后果

**积极**：
- 数据源配置和扫描历史可通过标准 API 查询（GET /v1/discovery/sources）
- 所有 Discovery 操作自动经过 ChangeEventInterceptor（审计）
- 未来可通过 Authorizer 控制谁能触发扫描

**消极**：
- DiscoverySource 和 DiscoveryReport 需要额外的数据库表和 JPA 实体（增加了平台复杂度）
- 与 OM 的 IngestionPipeline 模式一致，但 OM 的 Ingestion 是 Python 应用独立部署——
  Heirloom 的 Discovery 内嵌在 Java 服务中，大扫描可能影响 API 响应（需要异步化，Phase 2+）

## 参考

- OpenMetadata `IngestionPipeline` entity（对标 DiscoverySource）
- 设计 Spec 4b.7 节
