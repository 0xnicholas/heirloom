# ADR-007: 语义原语约束动力学原语的三条规则

## 状态
Accepted

## 日期
2026-05-28

## 上下文

Heirloom 将系统原语分为两个正交层（详见 ADR-002 对语义层安全模型的论述）：

- **语义原语**（Resource Type、Property、Relationship、Abilities、State Machine、Role）——回答「世界是什么样的」
- **动力学原语**（Action、Function）——回答「世界如何改变与计算」

关键问题：**语义原语和动力学原语之间应该如何约束？** ADR-002 解决了 Abilities 的存放位置（类型定义内 vs 外部 RBAC），本 ADR 解决的是「定义好的 Abilities 和状态机如何强制执行 Action 的合法性」——前者是静态声明，后者是动态约束。

如果动力学原语可以自由定义而不受语义原语的约束，那么 Abilities 和状态机就退化为了纯文档——它们表达了「应该怎样」，但 Action 开发者可以忽略。相反，如果在动力学原语定义时强制校验收到的硬约束，可以在类型系统层面保证「任何操作都是语义上合法的」——不依赖开发者的自律。

## 决策

**在 Action 和 Function 的定义阶段强制执行三条规则：**

### 规则一：Ability 门禁

Action 声明 `requires: X`——该 Ability X 必须在 target Resource Type 的语义定义中已声明。如果 Customer 类型未声明 `drop`，则任何以 Customer 为 target 且 `requires: drop` 的 Action 在**定义时即被 Schema Registry 拒绝注册**。不存在「定义有效但因权限不足而执行失败」的 Action——无效的定义根本不存在。

### 规则二：State 门禁

Action 声明 `gate: state = Y`——该状态 Y 必须是 target Resource Type 的状态机中已定义的合法状态。状态机定义了 Active → Frozen 但没有定义 Frozen → Draft，那么声明 `gate: target.state = Frozen` 且执行后目标状态为 Draft 的 Action 在定义时被拒绝。

### 规则三：类型一致性

Action 的 target 必须是已在 Schema Registry 中注册的 Resource Type。Action 的参数类型必须匹配该类型已定义的字段类型。Function 的输入对象类型和返回类型同样受此约束。

## 后果

**积极**：
- **安全在定义时而非执行时**：非法的 Action 不会在运行时抛出异常——它根本不会被成功定义
- **审计简化**：审查 Action 的安全性不再是「检查这段代码是否写了正确的 if 语句」——而是「检查该 Action 的 requires 和 gate 是否与 target 类型的 Abilities 和状态机一致」——后者可以在定义时自动完成
- **Agent 安全模型增强**：Agent 的 Role 授予某 Capability 之后，该 Capability 可以覆盖的 Action 范围由这三条规则决定——不会出现「Agent 有 mutate Capability 但调用了需要 drop 的 Action（因为 drop 型 Action 的存在受规则一约束）」

**消极**：
- Action 定义过程更繁琐——开发者必须先确保 target 类型的语义定义完备，再定义 Action
- 类型系统的检查逻辑本身需要维护和演化
- 某些合法的业务场景可能需要暂时「绕过」状态机约束（如纠正错误的数据状态）——当前模型不支持例外，需要拓展

## 备选方案

### 方案 A：运行时检查（不在定义时约束）
允许任意 Action 被定义，仅在运行时检查 Ability 和 State。

**放弃理由**：这回到了传统系统的模型——安全依赖开发者记得检查和测试。在 AI Agent 场景中，这意味着 Agent 可能调用一个「定义了但语义上不该存在」的 Action——安全保证又降级了。

### 方案 B：仅约束 Ability，不约束 State
在定义时检查 Ability，但 State 检查留到运行时。

**放弃理由**：能力（能不能做）和状态（做了之后合不合法）是两个正交的维度。仅约束 Ability 而放行 State 意味着一个已确认的订单可能被推进到一个非法状态——因为该 Action 虽然「有能力做」，但「语义上不该做」。这对于需要严格审计的场景是不可接受的。

## 相关 ADR

- [ADR-002](./002-abilities-as-type-contracts.md) — Abilities 为何在类型定义中而非外部 RBAC
- [ADR-006](./006-three-relationship-semantics.md) — Relationship 语义作为约束的来源之一
