# ADR-026: ResourceType Lifecycle — Six Stages

## 状态
Accepted

## 日期
2026-06-21

## 上下文

ResourceType 不是静态的——底层 Table 可能变化（新增列、删除列、删除表），
语义定义也可能需要修改（新增 Abilities、修改 StateMachine）。需要一个生命周期
模型来跟踪这些变化。

## 决策

**六阶段生命周期**：DISCOVERED → ACTIVE → EVOLVING → STALE → DEPRECATED → DELETED。

- DISCOVERED：Discovery 产出 Proposal，待审批
- ACTIVE：已注册，Agent 可用
- EVOLVING：底层 Column 变化，UPDATE Proposal 待审批——当前版本仍为 ACTIVE
- STALE：底层 Table/Column 被删除，MappingRule 失效——Agent 可查询但收到警告
- DEPRECATED：标记废弃——Agent 可查询但不可执行 Action
- DELETED：软删除（deleted=true）

## 后果

阶段转换通过 Proposal 审批触发（Phase 0 手动，Phase 1+ 可自动）。

## 参考

- 设计 Spec 4b.15 节
