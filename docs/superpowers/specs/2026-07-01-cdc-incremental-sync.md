# Spec: CDC 增量同步 — PostgreSQL Logical Replication

**日期**: 2026-07-01
**关联**: ADR-003 (Storage Separation), ADR-018 (Discovery as Entity), Phase 1.1 (Connector Framework)
**状态**: Draft v1
**范围**: 让 Heirloom 实时感知外部 PostgreSQL 数据变更——Agent 查询的不再是上一次全量扫描的快照

---

## 0. 问题陈述

当前 Heirloom 的数据新鲜度依赖手动触发的全量 Discovery。`SchemaExtractor` 扫描整个 schema，`DiscoveryService` 生成 ResourceType 定义，但**实际的 Resource 实例数据从未同步到 Heirloom**。

这意味着：
- Agent 通过 `QueryController` 查询的是外部 PG 的当前状态（直连查询），不是 Heirloom 管理的 Resource
- I-0 创建的 Resource Store 只能通过 REST API 手动填充
- Action 流水线的 `stepState` 读取 Resource.currentState，但这个 state 和外部 DB 的实际数据没有任何同步机制

**CDC 的目标**：当外部 PostgreSQL 表发生 INSERT/UPDATE/DELETE 时，Heirloom 自动同步对应的 Resource 实例——创建、更新字段、更新状态、标记删除。Agent 查询 Heirloom 的 Resource Store 就能获得接近实时的业务数据。

---

## 1. 设计决策

### 1.1 方案选择：pgoutput vs Debezium vs Trigger

| 方案 | 基础设施要求 | 延迟 | 运维复杂度 | 适用场景 |
|------|------------|------|-----------|---------|
| **pgoutput（本方案）** | PG 9.4+，`wal_level=logical` | 毫秒级 | 低——单进程 JDBC 连接 | Heirloom 当前规模 |
| Debezium + Kafka | Kafka 集群 + Connect 节点 | 毫秒级 | 高——需运维 Kafka | 企业级部署 |
| Trigger + 队列表 | 无额外配置 | 秒级 | 中——对源表有写入开销 | 无法开启 logical replication 的环境 |

**选择 pgoutput**：
- Heirloom 当前是单实例部署，不需要 Kafka 的分布式能力
- pgoutput 是 PostgreSQL 内置功能，零额外依赖
- JDBC 驱动从 PG 42.4+ 原生支持 replication protocol
- 未来可平滑迁移到 Debezium（pgoutput 是 Debezium PG connector 的底层实现）

### 1.2 pgoutput 工作原理

```
PostgreSQL
  ┌──────────────────────────────────────┐
  │ WAL (Write-Ahead Log)                │
  │  ┌────────────────────────────────┐  │
  │  │ logical decoding               │  │
  │  │   ↓                            │  │
  │  │ replication slot → pgoutput    │──┼──► Heirloom CdcEngine
  │  │   ↓              plugin         │  │   (JDBC replication connection)
  │  │ 变更事件流                       │  │
  │  │ {table, op, old, new, lsn}     │  │
  │  └────────────────────────────────┘  │
  └──────────────────────────────────────┘
```

1. Heirloom 向源 PG 创建 `PUBLICATION`（声明监听哪些表）和 `REPLICATION SLOT`（记录消费位点）
2. Heirloom 通过 JDBC replication connection 连接到 PG，开始 streaming
3. PG 将 WAL 中已提交事务的变更通过 pgoutput plugin 解码为逻辑事件
4. Heirloom 收到事件 → 映射为 Resource 操作 → 写入 Resource Store
5. 每处理一批事件，更新 LSN offset（持久化到 Heirloom 自己的表）
6. 重启时从上次的 LSN 恢复，不丢不重

### 1.3 为什么不直接用 Debezium

- Debezium 需要在 Kubernetes 或 Docker 上运行 Kafka + Connect 集群
- Heirloom 目前没有容器化部署，引入 Kafka 会让「第二个人跑起来」的门槛从 1 天变成 1 周
- pgoutput 的 JDBC 实现可以写在一个 Spring Service 里，不需要新进程
- 当 Heirloom 完成 docker-compose 部署后，可以评估升级为 Debezium（pgoutput 到 Debezium 的迁移是协议兼容的——都是 pgoutput plugin）

---

## 2. 架构

### 2.1 组件图

```
┌──────────────┐     ┌─────────────────────────────────┐
│ 外部 PG      │     │         Heirloom                │
│              │     │                                 │
│  ┌────────┐  │     │  ┌───────────────────────────┐  │
│  │customers│──┼─────┼──► CdcEngine                 │  │
│  │ orders  │  │ CDC │  │  - replication connection  │  │
│  │ ...     │  │事件 │  │  - slot management         │  │
│  └────────┘  │     │  │  - offset tracking          │  │
│              │     │  └───────────┬───────────────┘  │
└──────────────┘     │              │                   │
                     │              ▼                   │
                     │  ┌───────────────────────────┐  │
                     │  │ CdcEventMapper            │  │
                     │  │  - table → ResourceType    │  │
                     │  │  - column → field          │  │
                     │  │  - PK → RID               │  │
                     │  └───────────┬───────────────┘  │
                     │              │                   │
                     │              ▼                   │
                     │  ┌───────────────────────────┐  │
                     │  │ ResourceService            │  │
                     │  │  - upsert (INSERT/UPDATE)   │  │
                     │  │  - markDeleted (DELETE)     │  │
                     │  └───────────────────────────┘  │
                     │                                 │
                     └─────────────────────────────────┘
```

### 2.2 新组件

| 组件 | 职责 |
|------|------|
| **CdcSource** (entity) | 描述一个 CDC 数据源：PG 连接信息、publication 名称、slot 名称、监听的表列表 |
| **CdcEngine** (service) | 管理 replication slot 生命周期、建立 streaming connection、消费事件 |
| **CdcEventMapper** (service) | CDC 事件 → Resource 操作映射（表→ResourceType、列→field、PK→RID） |
| **CdcOffsetStore** (entity) | 持久化 LSN offset，支持断点续传 |

### 2.3 与现有系统的关系

| 现有组件 | CDC 如何使用 |
|---------|------------|
| `DiscoverySource` | CDC 复用 Discovery 的 schema 信息（表→ResourceType 映射），但不触发全量扫描 |
| `MappingRule` | CDC 通过 MappingRule 解析列名→字段名的映射（复现已有的 field→column 关系） |
| `ResourceService` | CDC 直接调用 `create()` / `updateFields()` / transitionState / 软删除 |
| `ChangeEventInterceptor` | Resource 变更自动产生审计事件（与手动 API 创建同等对待） |
| `SchemaExtractor` | CDC Source 注册时做一次 schema 提取，确保 Publication 中的表都有对应的 ResourceType |

---

## 3. Scope

### 3.1 In scope (v1)

| 能力 | 说明 |
|------|------|
| **CdcSource CRUD** | REST API 注册/管理 CDC 数据源（PG 连接、publication、slot、表列表） |
| **Publication 管理** | 自动在源 PG 创建/删除 PUBLICATION（`CREATE PUBLICATION heirloom_cdc FOR TABLE ...`） |
| **Replication slot 管理** | 自动创建/删除 replication slot（`CREATE_REPLICATION_SLOT ... LOGICAL pgoutput`） |
| **INSERT → Resource create** | 新行 → 生成 RID → `ResourceService.create()` |
| **UPDATE → Resource update** | 变更行 → 对比 old/new → `ResourceService.updateFields()` |
| **DELETE → Resource soft delete** | 删除行 → `Resource.markDeleted()` |
| **LSN offset 持久化** | 每次成功处理后写入 `cdc_offsets` 表，重启从该位点恢复 |
| **Idempotency** | LSN 级别的去重——相同 LSN 的事件不重复处理 |
| **单表监听** | 初始版本支持显式声明要监听的表（非自动全库监听） |
| **启动/停止/状态查询** | REST API：启动 CDC 引擎、停止、查询当前状态（lag、最后 synced LSN） |

### 3.2 Out of scope (v1)

- DDL 变更跟踪（新列、删列）— 需手动触发 Discovery 重新扫描
- TOAST 列处理（大文本字段的 UPDATE 可能不包含旧值）— v1 跳过 TOAST 列的对比，全量替换
- 多表事务原子性 — pgoutput 事件按事务提交顺序到达，但 Heirloom 不保证跨 Resource 的事务一致性
- 源 PG 的高可用切换 — 切换后需手动重建 slot
- MySQL / 其他数据库 CDC — 仅 PostgreSQL

---

## 4. 数据库 Schema

```sql
-- V16: CDC sources
CREATE TABLE cdc_sources (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    pg_host         VARCHAR(256) NOT NULL,
    pg_port         INTEGER      NOT NULL DEFAULT 5432,
    pg_database     VARCHAR(128) NOT NULL,
    pg_schema       VARCHAR(128) NOT NULL DEFAULT 'public',
    pg_username     VARCHAR(128) NOT NULL,
    pg_password     VARCHAR(256) NOT NULL,  -- encrypted at rest
    publication_name VARCHAR(128) NOT NULL,
    slot_name       VARCHAR(128) NOT NULL,
    watched_tables  JSONB        NOT NULL DEFAULT '[]',  -- ["customers", "orders"]
    status          VARCHAR(32)  NOT NULL DEFAULT 'STOPPED',  -- STOPPED, STARTING, RUNNING, ERROR
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- V16: CDC offset store
CREATE TABLE cdc_offsets (
    source_name     VARCHAR(128) NOT NULL,
    lsn             VARCHAR(64)  NOT NULL,   -- PG LSN format: 0/16B3748
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (source_name)
);
```

---

## 5. API

### 5.1 CdcSource CRUD

```
POST /v1/cdc/sources
{
  "name": "production-db",
  "pgHost": "10.0.1.5",
  "pgPort": 5432,
  "pgDatabase": "erp",
  "pgSchema": "public",
  "pgUsername": "heirloom_cdc",
  "pgPassword": "***",
  "watchedTables": ["customers", "orders", "inventory"]
}
→ 201 { ...source... }

GET /v1/cdc/sources
→ 200 { "items": [...] }

GET /v1/cdc/sources/{name}
→ 200 { ...source... }

DELETE /v1/cdc/sources/{name}
→ 204
```

### 5.2 CDC 引擎控制

```
POST /v1/cdc/sources/{name}/start
→ 200 { "status": "STARTING", "message": "Replication slot created, streaming begins" }

POST /v1/cdc/sources/{name}/stop
→ 200 { "status": "STOPPED" }

GET /v1/cdc/sources/{name}/status
→ 200 {
  "status": "RUNNING",
  "lastLsn": "0/16B3748",
  "lastSyncedAt": "2026-07-01T12:00:00Z",
  "lagSeconds": 0.5,
  "eventsProcessed": 15234
}
```

### 5.3 CDC 启动流程

```
POST /v1/cdc/sources/{name}/start 触发：

1. 验证 PG 连接可用
2. 检查 wal_level = logical
3. 检查 publication 是否存在，否则 CREATE PUBLICATION
4. 检查 replication slot 是否存在，否则 CREATE_REPLICATION_SLOT
5. 创建 JDBC replication connection
6. 开启 streaming 线程
7. 状态更新为 RUNNING
```

---

## 6. CdcEngine 核心逻辑

### 6.1 Event loop（伪代码）

```java
public void stream(CdcSource source) {
    // 1. 建立 replication connection
    PGReplicationStream stream = openReplicationStream(source);

    // 2. 从上次 offset 恢复
    String startLsn = offsetStore.getLastLsn(source.getName());
    if (startLsn != null) {
        stream.setFlushedLSN(LogSequenceNumber.valueOf(startLsn));
    }

    // 3. 消费循环
    while (running) {
        ByteBuffer msg = stream.readPending();  // 非阻塞

        if (msg != null) {
            CdcEvent event = decodePgoutputMessage(msg);
            
            // 过滤：只处理 watchedTables 中的表
            if (!source.getWatchedTables().contains(event.tableName())) {
                stream.setAppliedLSN(event.lsn());
                stream.setFlushedLSN(event.lsn());
                continue;
            }

            // 映射 + 执行
            switch (event.operation()) {
                case INSERT -> handleInsert(event);
                case UPDATE -> handleUpdate(event);
                case DELETE -> handleDelete(event);
            }

            // 推进 offset
            stream.setAppliedLSN(event.lsn());
            stream.setFlushedLSN(event.lsn());
            offsetStore.save(source.getName(), event.lsn().toString());
        }

        // 定期发送 standby status update（防止 slot 膨胀）
        stream.forceUpdateStatus();
    }
}
```

### 6.2 事件映射

```java
// CdcEventMapper
private void handleInsert(CdcEvent event) {
    String resourceType = resolveResourceType(event.tableName());  // customers → Customer
    String rid = buildRid(resourceType, event.newValues());        // default.Customer.{hash}
    Map<String, Object> fields = mapColumnsToFields(resourceType, event.newValues());
    resourceService.create(resourceType, "cdc-system", fields);
}

private void handleUpdate(CdcEvent event) {
    String rid = buildRid(resolveResourceType(event.tableName()), event.oldValues());
    Map<String, Object> changedFields = diffFields(event.oldValues(), event.newValues());
    if (!changedFields.isEmpty()) {
        resourceService.updateFields(rid, changedFields, 0L);  // version check relaxed for CDC
    }
}

private void handleDelete(CdcEvent event) {
    String rid = buildRid(resolveResourceType(event.tableName()), event.oldValues());
    Resource r = resourceService.getByRid(rid);
    r.setDeleted(true);
    resourceRepo.update(r);
}
```

### 6.3 RID 稳定性

CDC 的核心挑战：**UPDATE 和 DELETE 事件需要找到对应的 Resource**。这要求 RID 是**确定性的**——相同的主键值永远产生相同的 RID。

**RID 生成策略（CDC 模式）**：

```
外部表 customers，主键 id=42
→ RID = "default.Customer.{sha256_hex(42)[0:8]}"
→ 例: "default.Customer.a1b2c3d4"

下次 UPDATE id=42 的行：
→ 同样的 RID "default.Customer.a1b2c3d4"
→ ResourceService.updateFields(rid, ...) 找到正确的 Resource
```

这与 I-0 中随机生成的 RID 不同。需要 **RID 策略选择**：API 创建的 Resource 用随机 base62（无外部主键），CDC 创建的 Resource 用确定性 hash。

### 6.4 Idempotency

pgoutput 的 at-least-once 语义意味着同一事件可能被投递多次（重启后从 checkpoint 重放）。去重策略：

- 每个事件有唯一的 LSN
- `cdc_offsets` 表记录最后成功处理的 LSN
- 重启时从该 LSN 继续——pgoutput 保证从该点重放
- 事件处理本身是幂等的：INSERT 用 `ON CONFLICT (rid) DO NOTHING`，UPDATE 是覆盖写

---

## 7. 代码产物

| 文件 | 操作 | 说明 |
|------|------|------|
| `domain/CdcSource.java` | 新建 | JPA 实体：name, PG 连接信息, publication, slot, watchedTables, status |
| `domain/CdcOffset.java` | 新建 | JPA 实体：sourceName, lsn |
| `repository/CdcSourceRepository.java` | 新建 | CRUD + 按状态查询 |
| `repository/CdcOffsetRepository.java` | 新建 | get/save LSN offset |
| `service/CdcEngine.java` | 新建 | 核心引擎：replication stream 管理、事件消费循环 |
| `service/CdcEventMapper.java` | 新建 | pgoutput 消息解码 + 事件 → Resource 操作映射 |
| `service/CdcPgOutputDecoder.java` | 新建 | pgoutput binary protocol 解码器 |
| `web/CdcResource.java` | 新建 | REST 端点 |
| `db/migration/V16__cdc_sources.sql` | 新建 | Flyway 迁移 |
| `service/ResourceService.java` | 修改 | 新增 `upsertByRid()`——确定性 RID 的 INSERT-OR-UPDATE |

---

## 8. 验证标准

### 8.1 功能测试

- [ ] 注册 CDC source，启动引擎 → PG publication 和 slot 被创建
- [ ] INSERT 一行到源表 → Heirloom 中创建对应 Resource，RID 由主键确定性生成
- [ ] UPDATE 一行 → Heirloom 中对应 Resource 的字段更新
- [ ] DELETE 一行 → Heirloom 中对应 Resource 标记 deleted=true
- [ ] 停止引擎 → slot 保留，不丢数据
- [ ] 重启引擎 → 从上次 LSN 恢复，不丢不重
- [ ] 两次 INSERT 相同主键 → 第二次被跳过（幂等）
- [ ] 不监听的表发生变更 → 被正确忽略

### 8.2 边界测试

- [ ] 源表有复合主键 → RID 由所有主键列的 hash 生成
- [ ] 源表无主键 → 拒绝注册（CDC 要求主键，REPLICA IDENTITY FULL 作为降级选项）
- [ ] PG 连接中断 → 引擎进入 ERROR 状态，可手动重启
- [ ] WAL 积压（消费者慢于生产者）→ slot 不丢数据，lagSeconds 指标暴露
- [ ] 大事务（1000+ 行变更）→ 按事件逐条处理，不阻塞

### 8.3 性能

- [ ] 单表 INSERT 1000 行 → Heirloom 在 5 秒内完成同步
- [ ] 持续 100 events/s 的负载 → lag 稳定在 1 秒以内

---

## 9. 工作量

| 阶段 | 内容 | 天数 |
|------|------|------|
| **poc** | pgoutput 解码 + 核心 event loop + RID 确定性生成 | 2-3 天 |
| **core** | CdcSource CRUD + publication/slot 管理 + offset 持久化 | 2-3 天 |
| **mapping** | CdcEventMapper + ResourceService 集成 | 1-2 天 |
| **resilience** | 断线重连 + 幂等 + 状态机 + 错误处理 | 1-2 天 |
| **testing** | Testcontainers PG + CDC 事件模拟 | 2 天 |
| **合计** | | **8-12 天** |

---

## 10. 风险

| 风险 | 缓解 |
|------|------|
| pgoutput binary protocol 解码复杂 | JDBC `PGReplicationStream` 封装了底层解析；只需要理解 relation message 和 insert/update/delete message |
| replication slot 积压导致源 PG 磁盘满 | 定期 flush LSN + 监控 lag；设置 `max_slot_wal_keep_size` |
| 源 PG 版本差异（pgoutput 在 14+ 有变化） | v1 支持 PG 14-16；PG 版本检测 + 适配 |
| RID 确定性生成依赖主键稳定 | 注册时验证主键存在；主键变更视为 DELETE+INSERT |

---

## 11. 版本历史

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-07-01 | v0.1 | 初版：pgoutput 方案、架构、API、实现计划 |
