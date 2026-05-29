# Architecture Decision Records (ADR)

本目录记录了 Heirloom 项目中的关键架构决策。每条 ADR 遵循以下格式：

- **状态**：Accepted / Proposed / Superseded
- **上下文**：决策的背景和问题
- **决策**：我们做了什么选择及其理由
- **后果**：积极和消极影响
- **备选方案**：被考虑和放弃的其他方案

## ADR 索引

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
