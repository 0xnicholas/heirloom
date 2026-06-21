# Architecture Decision Records (ADR)

本目录记录了 Heirloom 项目中的关键架构决策。每条 ADR 遵循以下格式：

- **状态**：Accepted / Proposed / Superseded
- **上下文**：决策的背景和问题
- **决策**：我们做了什么选择及其理由
- **后果**：积极和消极影响
- **备选方案**：被考虑和放弃的其他方案

## ADR 索引

### 平台架构（新增——基于 OpenMetadata 参考，2026-06-21）

| 编号 | 标题 | 状态 |
|------|------|------|
| 010 | [平台架构参考——OpenMetadata 分层模式](./010-platform-architecture-reference.md) | Accepted |
| 011 | [EntityRegistry——中央实体注册表](./011-entity-registry.md) | Accepted |
| 012 | [HeirloomEntity——统一实体接口](./012-heirloom-entity-interface.md) | Accepted |
| 013 | [EntityRepository——标准生命周期基类](./013-entity-repository.md) | Accepted |
| 014 | [EntityResource——标准 REST 端点基类](./014-entity-resource.md) | Accepted |
| 015 | [分散式实体注册](./015-decentralized-registration.md) | Accepted |
| 016 | [ChangeEventInterceptor——自动审计](./016-change-event-interceptor.md) | Accepted |
| 017 | [可插拔 Authorizer](./017-pluggable-authorizer.md) | Accepted |
| 018 | [Discovery Engine 作为平台 Entity](./018-discovery-as-entity.md) | Accepted |
| 019 | [两阶段发现——提取→推断](./019-two-phase-discovery.md) | Accepted |
| 020 | [FQN 统一命名体系](./020-fqn-naming.md) | Accepted |
| 021 | [EntityRepository 包裹 Spring Data JPA](./021-jpa-wrapper.md) | Accepted |
| 022 | [Discovery Topology——声明式遍历树](./022-discovery-topology.md) | Accepted |
| 023 | [InferencePipeline——策略链 + Proposal 合并](./023-inference-pipeline.md) | Accepted |

### Heirloom 语义核心（原有——仍有效）

| 编号 | 标题 | 状态 |
|------|------|------|
| 001 | [语义中枢作为架构核心](./001-semantic-core-as-hub.md) | Accepted |
| 002 | [Ability 作为类型层契约（非外部 RBAC）](./002-abilities-as-type-contracts.md) | Accepted |
| 003 | [存储层分离（Resource Store / Graph Store / Event Log / Indexes）](./003-storage-separation.md) | Accepted |
| 004 | [JSON DSL 作为查询语言](./004-json-dsl-query-language.md) | Accepted |
| 005 | [九步 Action 校验流水线](./005-nine-step-action-pipeline.md) | Accepted |
| 006 | [Relationship 的三级语义（非通用边）](./006-three-relationship-semantics.md) | Accepted |
| 007 | [语义原语约束动力学原语的三条规则](./007-semantic-constrains-kinetic.md) | Accepted |
| 008 | [Function 作为一等领域学原语](./008-function-as-kinetic-primitive.md) | Accepted |
| 009 | [Perspective Engine 的不可绕过位置](./009-perspective-engine-placement.md) | Accepted |

### ADR 之间的关系

平台架构 ADR（010-023）定义了 Heirloom 作为**平台**的骨架：实体如何注册、如何
存储、如何暴露 API、如何审计。语义核心 ADR（001-009）定义了 Heirloom 作为
**语义系统**的核心概念：Resource、Ability、Relationship、Action、Function、
Perspective Engine。

两层关系：
- 语义核心的实体（ResourceType）是平台 Entity 的一种——遵循 `HeirloomEntity` 接口，
  走 `EntityRepository` 生命周期
- 平台层的 `ChangeEventInterceptor` 自动审计所有语义层的变更
- 平台层的 `Authorizer` 在 Phase 2 实现语义层的 Role → Capability → Action 授权

## 版本历史

| 日期 | 说明 |
|------|------|
| 2026-05-28 | 初始 ADR 系列（001-009）——基于白皮书 |
| 2026-06-21 | 平台架构 ADR（010-023）——基于 OpenMetadata 源码参考 |
