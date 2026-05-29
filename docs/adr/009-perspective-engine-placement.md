# ADR-009: Perspective Engine 的不可绕过位置

## 状态
Accepted

## 日期
2026-05-28

## 上下文

Heirloom 的核心安全承诺之一是：**同一 RID，不同 Role，不同 JSON。** Perspective Engine 负责在查询返回前基于调用者的 Role 裁剪字段和关系。

但「字段裁剪」可以在三个不同的架构位置上执行：

1. **API 网关 / BFF 层**：查询返回完整结果后，由 API 层根据 Role 过滤字段
2. **应用层中间件**：在每个消费者（Workshop、Agent SDK、REST API）内部各自实现过滤
3. **查询层内部**：在 Query Resolver 的查询计划阶段注入字段过滤，返回结果就是已裁剪的

位置 1 和 2 有一个共同风险：如果调用者能够绕过网关或应用中间件（如通过内部网络直接访问 Query Resolver），裁剪就被绕过——调用者获得完整数据。

这是 Heirloom 作为 AI 原生系统的关键安全问题：Agent 本身是调用者，如果 Perspective 过滤可以被绕过，Agent 就能通过技巧性查询获取超出其 Role 范围的数据。

## 决策

**Perspective Engine 嵌入在 Query Resolver 的查询计划生成阶段（步骤 5：Perspective Filter），而非 API 层或应用层。**

这意味着：
- 字段过滤在查询被分派到底层存储之前就已注入到查询计划中
- 被裁剪的字段不会离开 Query Resolver——它们从未被底层存储返回给调用者
- 不存在「返回完整结果后由上层裁剪」的中间态——API 层和应用层永远不会看到超出 Role 范围的数据

**具体实现**：
- Schema Registry 中的字段定义携带「对哪些 Role 可见」的元数据
- Query Resolver 在生成查询计划时，基于调用者 Role 查询 Schema Registry，将不可见字段从 `select` 和关系遍历路径中移除
- 对于语义搜索（`search` 块），不可见字段被排除在搜索索引的查询范围之外——Agent 甚至不能通过搜索侧信道推断不可见字段的值

## 后果

**积极**：
- **不可绕过**：任何人——包括具有 Query Resolver 直接访问权限的内部服务——都无法获取超出其 Role 范围的数据。安全边界不在网络层，在查询引擎内部
- **防御侧信道**：字段不仅不在返回结果中——它们被排除在查询范围之外，阻止了通过搜索排名、聚合结果等侧信道推断字段值
- **性能优化**：在查询计划中注入过滤后，底层存储可以跳过检索被裁剪的字段——减少数据传输量和反序列化开销

**消极**：
- **Schema Registry 耦合**：每次查询都需要咨询 Schema Registry（或缓存的 Role-字段映射），增加了 Query Resolver 的运行时依赖
- **调试困难**：开发者无法通过「直接查 Query Resolver 看完整结果，再手动用 Role 过滤」来调试 Perspective 逻辑——因为 Query Resolver 本身从不返回完整结果。需要专用的审计/模拟模式
- **Role 定义变更的延迟**：Role 的字段可见性变更后，缓存的映射表可能有一致性窗口——需要精心设计缓存失效策略

## 备选方案

### 方案 A：API 网关层过滤
在 API 网关（如 Kong、Envoy）中以插件形式实现字段过滤。查询返回完整结果，网关根据 JWT 中的 Role 声明裁剪字段。

**放弃理由**：任何绕过网关的请求（内部服务调用、Agent SDK 直连）都会获得完整数据。此外，大对象集的字段过滤在网关层会产生大量的网络传输和 CPU 开销——数据传输到网关后被丢弃比在存储端就排除更浪费。

### 方案 B：存储层过滤（Row-Level Security）
在各存储后端使用原生的行级/列级安全机制（如 PostgreSQL RLS）。

**放弃理由**：Heirloom 的存储是多模态的（Resource Store、Graph Store、Indexes），每个存储的 RLS 机制不同——跨存储的统一安全策略几乎不可能实现。此外，RLS 通常局限于行级过滤，Role 的字段级裁剪（同一个 Resource 的不同字段对不同 Role 可见）在大多数 RLS 实现中不支持。

## 相关 ADR

- [ADR-001](./001-semantic-core-as-hub.md) — Perspective Engine 与 Query Resolver 在中枢模式下的紧密集成
- [ADR-005](./005-nine-step-action-pipeline.md) — Pipeline 中 Perspective Filter 的步骤位置
