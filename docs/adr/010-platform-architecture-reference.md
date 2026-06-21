# ADR-010: 平台架构参考——OpenMetadata 分层模式 + 两层融合

## 状态
Accepted

## 日期
2026-06-21

## 上下文

Heirloom 需要从「单个 Java 服务 + 语义操作」演进为完整平台。参考 OpenMetadata
的 8 层架构模式，同时保留 Heirloom 独有的语义操作层。关键决策：元数据层和语义层
是两个逻辑层，共存在同一个平台内——不拆分为两个独立系统。

### 两层架构

Heirloom 不是「Heirloom + OpenMetadata 两个系统分工协作」。而是 Heirloom 自身
包含完整的元数据层（对标 OpenMetadata）+ 语义操作层（Heirloom 独有）。

- **元数据层**：自动发现和采集数据源 schema、血缘、质量、所有权、术语表
- **语义操作层**：在元数据之上提供 ResourceType、Abilities、StateMachine、
  Relationship、Action、Function——为 AI Agent 提供类型安全的操作界面

两层共存在同一个平台上：共享 EntityRegistry、EntityResource、EntityRepository、
ChangeEventInterceptor、Authorizer、FQN 体系。

### 采用的 OM 层

### 采用的层

| OM 层 | Heirloom 对应 | 理由 |
|-------|--------------|------|
| Entity Registry (`Entity.java`) | `EntityRegistry` | 所有实体类型统一登记，提供 `getRepository(entityType)` 等查询 |
| Entity Interface (`EntityInterface`) | `HeirloomEntity` | 统一实体接口，必需字段 + 可选 default-null 字段 |
| Entity Repository (`EntityRepository<T>`) | `EntityRepository<E>` | 标准生命周期：`prepare → setFQN → storeEntity → storeRelationships` |
| Entity Resource (`EntityResource<T,K>`) | `EntityResource<E>` | 标准 REST CRUD 端点基类 |
| Change Event Handler | `ChangeEventInterceptor` | 非 GET 请求自动审计 (Spring ResponseBodyAdvice) |
| Authorizer | `Authorizer` | 可插拔授权接口 (Phase 0 Noop → Phase 2 RoleBased) |

### 不采用的层

| OM 层 | 理由 |
|-------|------|
| JDBI3 DAO (`CollectionDAO`) | Heirloom 用 Spring Data JPA + PostgreSQL JSONB |
| Search Infrastructure (Elasticsearch) | Phase 0 不需要。搜索功能 Phase 3+ |

### 关键适应：Spring Boot vs Dropwizard

OM 使用 Dropwizard + JAX-RS + JDBI3。Heirloom 使用 Spring Boot + Spring MVC + JPA。
每个 OM 模式都需要找到 Spring 等价物：
- JAX-RS `ContainerResponseFilter` → Spring `ResponseBodyAdvice`
- JDBI3 `EntityRepository` → Spring Data JPA 包装类
- Dropwizard `@Path` → Spring `@RequestMapping`

## 后果

**积极**：
- 平台骨架清晰——新增实体类型有明确模式可循
- 与 OM 的可比性——团队可从 OM 代码理解 Heirloom 架构

**消极**：
- 增加了约 35 个新文件（entity/repository/web/interceptor/auth/discovery 包）
- Spring 的 DI 模型比 OM 的 `new Repository()` 更重，需要管理 Bean 依赖

## 备选方案

**方案 A：完全不参考 OM，自建平台骨架**
放弃理由：OM 的模式经过 LinkedIn 和数千企业的验证，自建容易遗漏关键横切关注点（审计、授权）。

**方案 B：完全复制 OM 架构（包括 JDBI3）**
放弃理由：当前已有 JPA 基础设施，迁移到 JDBI3 成本高且无收益。Spring Boot 更适合 Heirloom 团队的技能栈。

## 相关 ADR

- [ADR-011](./011-entity-registry.md) — EntityRegistry 详细设计
- [ADR-012](./012-heirloom-entity-interface.md) — HeirloomEntity 接口
- [ADR-013](./013-entity-repository.md) — EntityRepository 基类
- [ADR-014](./014-entity-resource.md) — EntityResource 基类
- [ADR-015](./015-decentralized-registration.md) — 分散式注册
- [ADR-016](./016-change-event-interceptor.md) — 自动审计
- [ADR-017](./017-pluggable-authorizer.md) — 可插拔授权

## 参考

- OpenMetadata `Entity.java`: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/Entity.java`
- OpenMetadata `EntityResource.java`: `_references/OpenMetadata-main/openmetadata-service/src/main/java/org/openmetadata/service/resources/EntityResource.java`
- OpenMetadata `DEVELOPER.md`: `_references/OpenMetadata-main/DEVELOPER.md`
