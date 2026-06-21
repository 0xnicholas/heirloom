# ADR-018: Discovery Engine——双重产出（元数据实体 + 语义 Proposal）

## 状态
Accepted

## 日期
2026-06-21 (updated 2026-06-21)

## 上下文

Discovery Engine 连接数据源。一次扫描需要同时产出两类实体：
1. **元数据实体**（Table、Column、Lineage）——对标 OpenMetadata 的元数据目录
2. **语义 Proposal**（ResourceType、Abilities、Relationship）——Heirloom 独有的语义层

## 决策

**Discovery Engine 一次扫描，双重产出。** Phase 1 (TopologyRunner) 提取 RawMetadata，
然后分两条路径处理。

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
