# ADR-001: 语义中枢作为架构核心

## 状态
Accepted

## 日期
2026-05-28

## 上下文

Heirloom 需要在异构数据源（PostgreSQL、REST API、Kafka、S3）和业务消费者（人类用户、AI Agent、BI 工具、工作流）之间建立一个中间层。在设计初期，我们探索了两种架构模式：

1. **语义层作为堆栈中的一层**——与其他层（存储、操作、消费）平级，上下游通过接口通信
2. **语义层作为架构中枢**——语义层位于数据世界和业务世界的中心，其他层围绕它呈放射型排列

我们观察到：在企业本体系统中，几乎所有请求路径都必须经过语义翻译（业务术语 ↔ 物理数据位置）。如果语义层只是一层，跨层调用会产生大量回环。此外，权限过滤（Perspective Engine）和查询解析（Query Resolver）需要紧密协作——分散在不同层会增加不一致的风险。

## 决策

**采用语义中枢模式（Semantic Core as Central Hub）**。语义层不是一层，而是连接数据世界与业务世界的唯一翻译器和安全边界。

语义中枢内部由四个紧密协作的子系统组成：
- **Schema Registry**：管理 Resource Type 定义（类型元数据）
- **Mapping Engine**：维护业务字段 → 物理数据源的映射
- **Query Resolver**：将语义查询翻译为底层执行计划
- **Perspective Engine**：基于调用者 Role 裁剪返回内容

集成层（数据接入）和操作层（写入校验）位于语义中枢的两侧，而非上下。治理层作为纵轴贯穿所有层。

## 后果

**积极**：
- 请求路径简洁：所有查询和操作经过语义中枢，不存在跨层回环
- Perspective Engine 和 Query Resolver 可以深度集成——在查询计划阶段就注入字段裁剪，无法被应用层绕过
- 单一信任边界：语义中枢是安全模型的唯一仲裁点

**消极**：
- 语义中枢成为系统瓶颈和单点——四个子系统之间紧密耦合，增加了复杂度
- 与经典分层架构的直觉不符，新加入的开发者需要理解这种非标准组织方式
- Schema Registry 自举需求（Schema Registry 自身也是 Resource）在中枢模式下更加突出

## 备选方案

### 方案 A：分层架构（Layer Stack）
将语义层作为存储层和操作层之间的一层，与其他层平级。

**放弃理由**：Query Resolver（翻译语义查询）和 Perspective Engine（裁剪结果）如果处于不同层，需要额外的跨层通信和重复的 Role 解析。查询路径会比中枢模式多 2-3 次内部调用。

### 方案 B：微服务架构
将四个子系统拆分为独立的微服务，通过 RPC 通信。

**放弃理由**：在 MVP 阶段引入微服务的运维复杂性超过其收益。此外，语义中枢的子服务之间调用极其频繁（每次查询都需要 Schema Registry + Mapping Engine + Perspective Engine），网络开销不可接受。

## 相关 ADR

- [ADR-003](./003-storage-separation.md) — 存储层如何在语义中枢模式下组织
- [ADR-004](./004-json-dsl-query-language.md) — Query Resolver 使用的查询语言
- [ADR-005](./005-nine-step-action-pipeline.md) — 操作层如何通过中枢执行写入
