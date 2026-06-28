# Heirloom Core Concepts

> 本文档汇总 Heirloom 的核心概念，用于统一团队对类型系统、安全模型、操作模型和架构组织的理解。
> 来源：白皮书 Part 1/2、附录及 ADR-001 ~ ADR-008、ADR-014、ADR-026。

---

## 1. 设计立场

Heirloom 是一个 **AI 原生的语义本体系统**。它解决的核心问题是：

> 当 AI Agent 成为企业数据的一等消费者时，如何让它在**不被信任**的前提下安全地理解和操作业务数据？

Heirloom 的回答是：**不依赖行为体的自我约束，而依赖系统的不可逾越边界。**

- Agent 不直接访问数据库；
- Agent 不依赖 prompt 被告知「不能删除」；
- Agent 和人类走完全相同的校验链；
- 有害操作在类型系统中即不可表达。

---

## 2. 两个正交的原语层

Heirloom 把系统能力分为两个正交层：

| 层级 | 回答的问题 | 包含的原语 |
|------|-----------|-----------|
| **语义原语（Semantic Primitives）** | 世界是什么样的？ | Resource Type、Property、Relationship、Abilities、State Machine、Role |
| **动力学原语（Kinetic Primitives）** | 世界如何改变与计算？ | Action、Function |

**核心规则：语义原语是动力学原语的硬边界。** 任何 Action 或 Function 能做的事情，必须是语义原语已声明允许的事情。

---

## 3. 语义原语

### 3.1 Resource（资源）

**Resource 是 Heirloom 的一等业务实体**，也是 AI Agent 理解和操作企业数据的唯一界面。

每个 Resource 由以下要素构成：

| 要素 | 含义 | 对 AI Agent 的意义 |
|------|------|------------------|
| `rid` | 全局唯一、永不变化的 Resource Identifier | 稳定的实体引用，不随数据源迁移而失效 |
| `type` | 所属 Resource Type | 理解能力边界和可用属性 |
| `owner` | 当前所有者 | 查询所有权链，理解权限传播路径 |
| `state` | 当前生命周期状态 | 操作合法性可通过状态图预先判断 |
| `fields` | 业务属性键值对 | 可读写的结构化数据 |
| `relationships` | 指向其他 Resource 的关系 | 遍历关系图，理解依赖和关联 |
| `events` | 不可变事件流 | 获取上下文的时序信息 |
| `version` | 单调递增版本号 | 检测并发冲突 |

Resource 不是数据库记录的投影。它有独立的生命周期、稳定的 RID 和完整的事件历史。

### 3.2 Resource Type（资源类型）

Resource Type 定义一类 Resource 的语义契约：

- 字段（Property）及其类型
- Abilities（能力标记）
- 状态机（State Machine）
- 可参与的关系类型
- 可用的 Action 和 Function

Resource Type 在 Schema Registry 中注册，并受治理流程（Proposal → Branch → Review → Merge）控制。

### 3.3 Abilities（能力标记）

Abilities 是在 **Resource Type 定义时声明的类型级契约**。它们回答 Agent 最核心的安全问题：「我能对这个实体做什么？」

| Ability | 含义 | 典型 Agent 约束 |
|---------|------|----------------|
| `key` | 有全局唯一 RID，可被独立寻址 | 默认可用 |
| `store` | 可被嵌套在其他 Resource 内部 | 按类型需要 |
| `query` | 可被搜索和列表查询 | 大多数 Agent 可持有 |
| `mutate` | 字段可被修改 | **通常不应授予通用 Agent** |
| `transfer` | 所有权可被显式转移 | **高风险操作** |
| `copy` | 可被克隆 | 按业务需要 |
| `drop` | 可被销毁（逻辑删除） | **通常绝不授予 Agent** |
| `freeze` | 可被冻结为不可变状态 | 按业务需要 |

**关键原则**：如果 Resource Type 未声明 `drop`，则没有任何 Role——包括 Admin——能够创建可删除该类型实例的 Action。安全边界由类型定义决定，不由运行时配置决定。

### 3.4 State Machine（状态机）

每个 Resource Type 声明自己的状态图：节点、合法的有向边、每条边对应的触发条件。

- 非法迁移在 Action 定义时或运行时即被拒绝；
- 不需要在 Action 代码中手写 `if (status === 'frozen') return error`；
- LLM 提议「将已归档合同改回草稿」时，状态图中不存在的 Frozen → Draft 边会在类型层终止该请求。

### 3.5 Relationship（关系）

Heirloom 用三种精确语义取代传统图数据库中的无差别「边」：

| 关系类型 | 生命周期依赖 | 删除行为 | 权限传播 | 所有权可转移 |
|---------|------------|---------|---------|------------|
| **Ownership** | 被拥有方依赖拥有方 | 拥有方删除 → 被拥有方级联处理 | 拥有者的 Capability 向下传播 | 是 |
| **Reference** | 被引用方不依赖引用方 | 被引用方删除 → 引用断裂（可检测、可告警） | 不传播 | 否 |
| **Association** | 双方独立 | 任意一方删除不影响另一方 | 不传播 | 否 |

Agent 在执行写操作前，可以通过关系语义预先判断级联后果。

### 3.6 Role（角色）

Role 回答「你是谁」，在三个作用域上授予：

1. **Ontology 全局级**：如 `SecurityAuditor`
2. **Resource Type 级**：如 `Customer.Manager`
3. **Resource Instance 级**：如 `Customer#123 的 Owner`

Role 是 Actor 与 Capability 之间的静态授权层。

---

## 4. 动力学原语

### 4.1 Action（操作）

Action 是结构化写入操作，是改变 Resource 状态的唯一路径。

Action 的执行经过九步校验流水线：

| 步骤 | 名称 | 职责 |
|------|------|------|
| 1 | Auth | 解析调用者身份（人类 / Agent / Automation） |
| 2 | Role | 查询调用者在目标作用域上的 Roles |
| 3 | Capability | 从 Role 派生当前有效的 Capability |
| 4 | Gate | 校验 Capability 是否覆盖请求的 Ability |
| 5 | State | 校验 target Resource 当前状态是否允许该操作 |
| 6 | Validate | 执行业务规则验证 |
| 7 | Execute | 写入 Resource Store + 更新索引 |
| 8 | Event | 追加不可变事件（成功或被拒绝都记录） |
| 9 | Notify | 发布变更事件，触发下游 Automation |

**Action 定义时必须满足三条规则**：

1. **Ability 门禁**：`requires` 的 Ability 必须在 target Resource Type 中已声明；
2. **State 门禁**：`gate` 的状态必须是状态机中的合法状态；
3. **类型一致性**：target 和参数类型必须匹配 Schema Registry 中的定义。

### 4.2 Function（函数）

Function 是本体原生的只读计算逻辑。它接收对象或对象集作为输入，读取属性值，遍历关联，返回计算结果。

| | Action | Function |
|------|--------|----------|
| 改变 Resource 状态？ | 是 | 否 |
| 需要 Capability | 按操作所需的 Ability | 仅需 `query` |
| 产生审计事件 | 强制 | 可选 |
| 调用者 | 人类、Agent、Automation | Action 内部、应用、Agent |
| 示例 | `update_tier()`、`approve_order()` | `risk_score()`、`lifetime_value()` |

Function 与 Query 的区别：Query 是数据检索；Function 是可执行计算逻辑，适合跨对象、多步骤、调用外部服务的业务计算。

### 4.3 Automation（自动化）

Automation 不是独立原语。它是 **Event Log 事件监听 + 条件判断 + Action/Function 调用链** 的组合。

例如：当库存低于阈值时，自动触发 `notification.send` Action。

---

## 5. 安全模型：Actor → Role → Capability → Action

| 概念 | 含义 | 类比 |
|------|------|------|
| **Actor** | 调用者：人类用户、AI Agent、自动化工作流 | 身份 |
| **Role** | Actor 在特定作用域上被授予的静态身份 | 职位 |
| **Capability** | 从 Role 派生的、访问特定 Resource 的通行证，具有时效性和可撤销性 | 门票 |
| **Action** | 唯一的写入路径 | 具体操作 |

**关键原则**：Agent 与人类平权。三者走完全相同的校验链。Agent 的边界是 Role 的边界，没有额外的「Agent 专用安全层」。

---

## 6. 语义中枢

Heirloom 采用**语义中枢（Semantic Core as Central Hub）**架构。语义层不是堆栈中的一层，而是连接数据世界与业务世界的唯一翻译器和安全边界。

语义中枢由四个子系统构成：

| 子系统 | 职责 |
|--------|------|
| **Schema Registry** | 管理 Resource Type、Abilities、状态机、Role 定义 |
| **Mapping Engine** | 维护「业务字段 → 物理数据源」的映射 |
| **Query Resolver** | 将 LLM-friendly 的 JSON 语义查询翻译为底层执行计划 |
| **Perspective Engine** | 基于调用者 Role 裁剪返回的属性和关系 |

---

## 7. 存储层

存储按访问模式分离：

| 组件 | 内容 | 技术选型参考 |
|------|------|-------------|
| **Resource Store** | Resource 主体数据 | 文档数据库 |
| **Graph Store** | Resource 间关系 | 属性图数据库 |
| **Event Log** | 不可变操作事件流 | 日志存储 |
| **Indexes** | 属性、全文、向量索引 | Elasticsearch / pgvector |

---

## 8. 生命周期与治理

### 8.1 Resource 生命周期

1. 定义与建模（Resource Type 定义）
2. 数据实例化（从数据源物化为 Resource 实例）
3. 状态交互（通过 Action 层变更）
4. 自动化执行（状态机 + Automation）
5. 治理与版本（Schema 变更提案）
6. 演化与反馈（Agent 使用模式反馈到能力审查）

### 8.2 ResourceType 生命周期

`DISCOVERED` → `ACTIVE` → `EVOLVING` → `STALE` → `DEPRECATED` → `DELETED`。

阶段转换通过 Proposal 审批触发。

### 8.3 治理流程

Schema 变更遵循：

```
Proposal → Branch 开发 → 审批 → Merge → 部署
```

治理操作本身也受 Abilities 模型约束——修改 Schema Registry 需要相应的 Capability。

---

## 9. 概念关系图

```
                    Actor
                      │
                      ▼
                    Role
                      │
                      ▼
                Capability
                      │
                      ▼
        ┌─────────────┴─────────────┐
        │                           │
        ▼                           ▼
    Action（写入）              Function（只读）
        │                           │
        ▼                           ▼
   Resource ──────────────► Resource Type
   (实例)                       (类型定义)
        │                           │
        ├── fields ─────────────────┤
        ├── state ──────────────────┤
        ├── relationships ──────────┤
        └── events ─────────────────┘

Schema Registry ◄──── 语义中枢 ────► Mapping Engine
                      │
            ┌─────────┴─────────┐
            ▼                   ▼
      Query Resolver      Perspective Engine
```

---

## 10. 一句话定义速查

| 概念 | 一句话定义 |
|------|-----------|
| **Resource** | 有 RID、类型、所有者、状态、字段、关系和事件历史的业务实体 |
| **Resource Type** | Resource 的语义契约，含字段、Abilities、状态机和关系声明 |
| **Ability** | Resource Type 在类型层声明的能力标记，决定该类实例允许发生什么 |
| **State Machine** | Resource Type 声明的合法状态迁移图 |
| **Relationship** | Ownership / Reference / Association 三种精确语义关系 |
| **Action** | 改变 Resource 状态的唯一写入路径，经过九步校验流水线 |
| **Function** | 只读计算原语，可被 Action、Agent、应用调用 |
| **Role** | Actor 在特定作用域上的身份，静态授权 |
| **Capability** | 从 Role 派生的、访问 Resource 的临时通行证 |
| **Schema Registry** | 管理所有语义定义的运行时字典 |
| **Mapping Engine** | 业务字段到物理数据源的映射表 |
| **Query Resolver** | 语义查询到执行计划的翻译器 |
| **Perspective Engine** | 基于 Role 裁剪返回内容的投影引擎 |
| **Event Log** | 记录所有 Action 成功与拒绝尝试的不可变事件流 |
| **Automation** | Event + 条件 + Action/Function 调用链的组合 |
