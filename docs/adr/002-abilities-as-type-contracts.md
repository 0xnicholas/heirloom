# ADR-002: Ability 作为类型层契约（非外部 RBAC）

## 状态
Accepted

## 日期
2026-05-28

## 上下文

企业本体系统需要回答一个核心安全问题：「谁能对什么实体做什么操作？」传统方案将安全模型分为两层：

- **类型层**：定义 Resource Type 的结构（字段、关系、状态）
- **安全层**：在独立系统（RBAC / ACL / Policy Engine）中定义谁能执行什么操作

这种分离在 AI Agent 场景中暴露了结构性风险：Agent 的安全边界依赖于「安全层配置是否与类型定义一致」。如果一个 Resource Type 在业务上不应被任何角色删除，但安全层管理员失误配置了删除权限，Agent 就会获得删除能力。安全保证依赖于两层之间的人工对齐。

## 决策

**Abilities 声明为 Resource Type 定义的一部分**——`query`、`mutate`、`transfer`、`copy`、`drop`、`freeze`、`key`、`store` 这八种能力标记在建模时确定，而不是在 RBAC 面板中配置。

- 如果一个 Resource Type 未声明 `drop`，则**不存在**任何方式创建一个能删除该类型实例的 Role
- 如果一个 Resource Type 未声明 `freeze`，则实例永远不能被冻结
- 事后追加新的 Ability 必须通过 Schema 变更提案（Proposal）流程

Role 回答「谁被授予了什么 Ability 在什么作用域上」——这是安全模型的动态层。Abilities 回答「这个类型根本上允许什么」——这是安全模型的静态层。

## 后果

**积极**：
- 零信任的架构级保证：安全不由人工配置的准确性决定，由类型定义的确定性决定
- AI Agent 的行为边界不可被「配置错误」扩大——Agent 能够做到的操作，是其目标类型的 Abilities 和其 Role 中授予的 Capability 的交集。交集的上界由 Abilities 决定，下界由 Role 决定
- 安全审计变得简单：检查「该类型的 Abilities 是否应当包含此能力」比检查「该用户的 500 条 RBAC 规则中是否有不匹配的一项」更容易

**消极**：
- 紧急操作场景受限：无法临时授予某人删除能力来修正错误数据
- 建模阶段负担增加：建模者必须在定义 Resource Type 时做出安全的长期判断——而业务需求可能变化
- Abilities 的粒度固定在类型级别，无法表达「允许删除草稿状态的订单，但不允许删除已确认的订单」这类条件式限制（需未来引入条件式 Abilities）

## 备选方案

### 方案 A：传统 RBAC 外置
Abilities 存储在外部的 RBAC/Policies 表中，Resource Type 定义只管结构。

**放弃理由**：无法在架构层面防止配置错误。Agent 安全模型的可靠性降低到「管理员是否正确配置了策略」。在 AI Agent 自主操作的场景中，这个风险不可接受。

### 方案 B：混合模型（Abilities 在类型层声明，但可被 Admin 覆盖）
允许 Admin Role 绕过类型层的 Ability 限制。

**放弃理由**：这消解了类型层安全的全部意义。一旦存在「覆盖」机制，安全保证又回到了「谁持有 Admin Role」这一级——而 Admin 可能被滥用或被盗用。

## 相关 ADR

- [ADR-005](./005-nine-step-action-pipeline.md) — Action 校验流水线如何检查 Abilities
- [ADR-007](./007-semantic-constrains-kinetic.md) — Abilities 和状态机如何约束动力学原语的定义
