# ADR-005: 九步 Action 校验流水线

## 状态
Accepted

## 日期
2026-05-28

## 上下文

所有对 Resource 的写入操作必须经过 Action 层——这是 Heirloom 与 Palantir 都坚持的原则（禁止直接 UPDATE 底层数据库）。但「经过 Action 层」的具体校验流程有多深，是一个需要权衡的问题。

校验步骤过少（如仅检查 Role 和 Auth）：Agent 可能在具有合法 Role 的情况下推进一个非法状态迁移（如将 Frozen 的合同改回 Draft），或绕过业务规则验证。

校验步骤过多（如在每个 Action 中加入所有可能的检查）：延迟增加，且部分检查（如「参数是否合法」）和另一部分检查（如「调用者身份是否有效」）混在一起，降低了流水线的可审计性。

## 决策

**采用九步校验流水线，将不同性质的检查分离为独立步骤，按序执行：**

| 步骤 | 名称 | 职责 |
|------|------|------|
| 1 | Auth | 解析调用者身份（人类 / Agent / Automation） |
| 2 | Role | 查询调用者在目标作用域上的 Roles |
| 3 | Capability | 从 Role 派生当前有效的 Capability |
| 4 | Gate | 校验 Capability 是否覆盖请求的 Ability |
| 5 | State | 校验 target Resource 当前状态是否允许该操作 |
| 6 | Validate | 执行业务规则验证（字段格式、值域、引用完整性） |
| 7 | Execute | 写入 Resource Store + 更新索引 |
| 8 | Event | 追加不可变事件（成功或被拒绝都记录） |
| 9 | Notify | 发布变更事件（触发下游订阅者和 Automation） |

**被拒绝的操作也产生事件**（步骤 8 在拒绝路径上同样执行）——Event Log 记录了「谁尝试过但被拒绝了什么」，这是 Agent 行为审计的关键数据源。

**Function** 走简化路径：Auth → Role → Capability（仅需 query） → Execute，跳过 State 和 Validate，Event 可选。

## 后果

**积极**：
- 每一步有单一、明确的职责——便于测试、审计和独立演化
- **Agent 行为审计的数据基础**：被拒绝的操作进入 Event Log——这是改进 Agent Role 定义的关键信号源。高频拒绝暗示「Agent 被授予的能力不足」或「Agent 被给予了错误的操作预期」。拒绝事件是 Role 迭代的输入，而非需要被隐藏的错误
- 步骤分离意味着：即使某个 Action 的开发者在 Validate 步骤中忘了写某个检查，前面的 Gate（步骤 4）和 State（步骤 5）仍然提供类型层的安全保障

**消极**：
- 每次 Action 调用有多次内部查询开销（Role 解析、State 读取等）
- 对延迟敏感的场景（如高频写入）不适用
- 流水线的严格顺序性——如果步骤 3 的 Capability 检查很重，所有后续步骤都必须等待

## 备选方案

### 方案 A：轻量校验（Auth → Role → Execute）
仅检查身份和 Role，跳过 State、Validate 和 Event。

**放弃理由**：将太多安全责任推给 Action 代码的开发者。Agent 安全模型降级为「工具函数是否写得正确」。

### 方案 B：并行校验
将 Role / Capability / State / Validate 并行执行，提高性能。

**放弃理由**：步骤间有依赖（State 检查需要知道 target Resource 的当前状态，这可能依赖于 Capability 检查是否通过——如果调用者连 query 能力都没有，就不应该读取 Resource 的状态）。并行化引入的复杂性（超时、部分失败、回滚）超过了延迟收益。

## 相关 ADR

- [ADR-002](./002-abilities-as-type-contracts.md) — 流水线步骤 4（Gate）所校验的 Abilities 的来源
- [ADR-006](./006-three-relationship-semantics.md) — 流水线步骤 5（State）所校验的状态机来源
- [ADR-007](./007-semantic-constrains-kinetic.md) — 定义时约束如何减少流水线中的运行时检查
- [ADR-008](./008-function-as-kinetic-primitive.md) — Function 的简化校验路径
- [ADR-009](./009-perspective-engine-placement.md) — Pipeline 中 Perspective Engine 的安全位置
