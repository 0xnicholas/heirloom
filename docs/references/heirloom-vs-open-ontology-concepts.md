# Heirloom 与 Open Ontology 核心概念对比分析

> 对比来源：
> - Heirloom：`docs/core-concepts.md`、白皮书 Part 1/2、ADR-001 ~ ADR-008
> - Open Ontology：https://open-ontology.com/docs/concepts（内容与首页一致）
> 更新日期：2026-06-28

---

## 1. 总体定位对比

| 维度 | Heirloom | Open Ontology |
|------|---------|---------------|
| **核心命题** | 为 AI Agent 提供类型安全的业务语义操作层 | 把业务工作流变成可运行的 operational substrate |
| **安全哲学** | 类型层 Abilities 使非法操作不可表达 | 规则/约束/Process 显式化，闭环检测与修复 |
| **数据哲学** | Resource 是一等实体 | Triple（EAV）是一等实体 |
| **Agent 地位** | 与人类平权的一等消费者 | 一等消费者 |
| **当前成熟度** | 设计/早期实现阶段 | Research preview |

---

## 2. 原语层对比

### 2.1 概念映射表

| Open Ontology 原语 | Heirloom 对应概念 | 对应程度 | 说明 |
|-------------------|------------------|---------|------|
| **Entity** | **Resource / Resource Type** | ⚠️ 部分对应 | OO 的 Entity 更接近 Heirloom 的 Resource Type 定义；Heirloom 区分类型（Resource Type）和实例（Resource） |
| **Relationship** | **Relationship** | ⚠️ 部分对应 | OO 的关系带时间语义；Heirloom 的 Relationship 是 Ownership/Reference/Association 三级精确语义 |
| **Query** | **Query / Function** | ⚠️ 部分对应 | OO 的 Query 是保存的 Datalog 模式；Heirloom 分为声明式 Query（JSON DSL）和可编程 Function |
| **Mutation** | **Action（mutate）** | ✅ 高度对应 | 都是显式状态变更操作 |
| **Action** | **Action（含 notification.send 等）** | ✅ 高度对应 | 都是受治理的工具调用 |
| **Process** | **Automation / Action 编排** | ⚠️ 部分对应 | OO 的 Process 是一等 DAG 原语；Heirloom 的 Automation 是 Event + Action/Function 组合，非独立原语 |
| **Constraint** | **State Machine / Validation Rules** | ⚠️ 部分对应 | OO 的 Constraint 是持续评估的 Datalog 查询；Heirloom 用状态机和 Action Validate 步骤表达约束 |
| **View** | **Perspective Engine / Workshop** | ✅ 对应 | 都是基于 Role/身份裁剪的 UI 投影 |
| **Workspace** | **Role + Perspective** | ⚠️ 部分对应 | OO 的 Workspace 是面向角色的仪表盘；Heirloom 用 Role + Capability + Perspective Engine 组合实现 |

### 2.2 关键差异

#### 差异一：核心抽象不同

**Open Ontology：Everything is a Triple**

```
[entity, attribute, value, timestamp]
```

- Entity 是三元组中的 subject；
- Relationship 是 `[:a :rel-type :b t]`；
- Constraint 是持续运行的 Datalog 查询；
- Process 是当 Constraint 触发后执行的 DAG；
- 所有历史、审计、撤销都内建在 triple 模型中。

**Heirloom：Everything is a Resource**

```
Resource = rid + type + owner + state + fields + relationships + events + version
```

- Resource 是封装了身份、生命周期、能力、所有权、审计的语义实体；
- Triple 可以是 Resource 内部实现细节，但不是最高抽象；
- 类型层安全（Abilities）和状态机是 Resource 的核心属性。

#### 差异二：安全模型不同

| | Open Ontology | Heirloom |
|---|--------------|----------|
| **安全边界位置** | Constraint / Process 前置条件 | Resource Type 的 Abilities + State Machine |
| **删除能力控制** | 通过 Constraint 检测「不应存在的状态」 | 通过 `drop` Ability：未声明则不可表达 |
| **权限传播** | Relation 语义（未明确三级） | Ownership 自动传播 Capability |
| **Agent 校验链** | query → act → assert → explain | Auth → Role → Capability → Gate → State → Validate → Execute → Event → Notify |

**例子**：防止 Agent 删除 Customer。

```lisp
;; Open Ontology 方式：通过 Constraint 检测违规后触发 Process
(define-constraint customer-should-not-be-deleted-by-agent
  (query (find [?c])
    (where
      [[?c :_meta/deleted true]
       [?c :_meta/actor-type "agent"]]))
  (severity error))
;; 已经发生的事后检测
```

```
;; Heirloom 方式：Customer Type 不声明 drop Ability
(define-resource-type Customer
  (abilities [query mutate transfer freeze]))  ;; 没有 drop
;; Agent 永远无法获得 delete Customer 的 Capability
```

Heirloom 的方式是**事前不可表达**；Open Ontology 的方式是**事后检测 + 修复**。

#### 差异三：状态建模不同

| | Open Ontology | Heirloom |
|---|--------------|----------|
| **状态机** | 无显式状态机；通过 Constraint + Process 隐式表达 | 显式声明状态图 |
| **非法迁移** | Constraint 触发后由 Process 处理 | 在类型层或 Action 定义时即被拒绝 |
| **表达能力** | 更灵活，可表达复杂业务规则 | 更严格，可证明性强 |

例子：Frozen 合同不能改回 Draft。

- **Open Ontology**：写一个 Constraint 检测「状态为 Frozen 且存在变为 Draft 的操作事实」，然后触发 Process 处理。
- **Heirloom**：状态机中没有 Frozen → Draft 的边，Action 在定义时即被拒绝注册。

#### 差异四：查询语言不同

| | Open Ontology | Heirloom |
|---|--------------|----------|
| **查询语言** | Datalog | JSON DSL + Function |
| **对 LLM 友好度** | 中等（Datalog 语法严格但递归强） | 高（JSON 结构化，LLM 生成稳定） |
| **表达能力** | 强（递归查询、图遍历、规则推导） | 中等（语义查询 + 代码级 Function 补充） |

---

## 3. 架构层对比

### 3.1 分层架构

```
Open Ontology:                    Heirloom:
┌─────────────────────┐          ┌─────────────────────┐
│ Application         │          │ Consumer Layer      │
│ (Web/API/CLI/MCP)   │          │ (Agent SDK/Workshop)│
├─────────────────────┤          ├─────────────────────┤
│ Runtime             │          │ Operation Layer     │
│ query→act→assert→explain      │ (Action/Function)   │
├─────────────────────┤          ├─────────────────────┤
│ Compiler            │          │ Semantic Core       │
│ Lisp/TS → IR        │          │ (Schema/Mapping/    │
├─────────────────────┤          │  Query/Perspective) │
│ Language            │          ├─────────────────────┤
│ Lisp/TS DSL         │          │ Storage Layer       │
├─────────────────────┤          │ (Resource/Graph/    │
│ Database            │          │  Event/Indexes)     │
│ Time-travel triple  │          └─────────────────────┘
└─────────────────────┘
```

### 3.2 关键组件对应

| Open Ontology | Heirloom | 说明 |
|--------------|----------|------|
| Time-traveling triple store | Event Log + Resource Store + Graph Store | OO 用统一 triple store；Heirloom 按访问模式分离存储 |
| Lisp/TypeScript DSL | Schema Registry + JSON DSL | OO 用代码定义本体；Heirloom 用 Schema Registry 管理定义，JSON DSL 供查询 |
| Compiler / IR | Schema Registry 内部模型 + Proposal 流程 | 两者都强调版本控制、diff、原子部署 |
| Runtime 闭环 | Action 九步流水线 + Automation | OO 的 runtime 持续评估 Constraint；Heirloom 的 Action 流水线在执行时校验 |
| MCP 接口 | AI Agent SDK | 两者都直接服务 Agent |

---

## 4. 各自优势

### 4.1 Open Ontology 的优势

1. **统一数据模型**：所有东西都是 triple，存储、审计、历史、关系用同一套模型表达，简洁优雅。
2. **规则驱动闭环**：Constraint → Violation → Process 能自然表达「当 X 发生时应该做 Y」的业务流程。
3. **Schema-free 灵活性**：新属性无需迁移，适合快速演化的业务场景。
4. **Hindley-Milner 类型推断**：DSL 体验好，无需类型注解。
5. **时间旅行内置**：point-in-time 查询是存储模型的原生能力。

### 4.2 Heirloom 的优势

1. **类型级安全**：Abilities 在类型定义层强制，非法操作不可表达。
2. **显式状态机**：状态迁移在定义时可证明，Agent 无法推进非法状态。
3. **三级关系语义**：Ownership/Reference/Association 精确控制级联、权限传播、所有权转移。
4. **LLM-friendly 接口**：JSON DSL 比 Datalog 更容易被 LLM 稳定生成。
5. **统一安全链**：Auth → Role → Capability → Action 完整覆盖人类、Agent、Automation。
6. **元数据目录一体化**：自含 DataHub/OpenMetadata 级元数据能力。

---

## 5. 对 Heirloom 设计的启示

### 5.1 可借鉴 Open Ontology 的地方

1. **统一事件/审计模型**：Heirloom 的 Event Log 可以进一步明确为 triple 序列，使审计、回放、point-in-time 查询更统一。
2. **规则引擎补充**：在 Action Validate 步骤之外，可引入声明式 Constraint 机制，用于检测跨 Resource 的业务规则（如「所有活跃供应商必须有有效证书」）。
3. **Schema-free 扩展字段**：Resource 的 `fields` 可以允许运行时附加轻量属性（受 Perspective Engine 控制可见性），同时保留类型层 Abilities 不变。
4. **Lisp/TS DSL 作为可选定义层**：除了 Schema Registry 的 JSON/YAML，可以提供 Lisp DSL 供高级本体工程师使用。
5. **Terraform-style 部署计划**：Schema 变更前生成 plan，原子 apply，对治理更友好。

### 5.2 Heirloom 应坚持的差异化

1. **Resource 作为最高抽象**：不要退化为 triple-first，否则 Abilities 和状态机会被弱化。
2. **Abilities 类型层强制**：这是 Heirloom 安全模型的核心，不应被 Constraint/Process 替代。
3. **显式状态机**：比规则驱动更严格、更可证明，适合高 stakes 的企业场景。
4. **三级关系语义**：这是 Heirloom 相对所有竞品（包括 Open Ontology）的独特优势。

---

## 6. 结论

Open Ontology 和 Heirloom 都想解决同一个问题：**给企业 AI Agent 一个受治理、可审计、可回放的语义操作层**。但两者选择了不同的核心抽象：

- **Open Ontology 选择「事实 + 规则 + 闭环」**：用 triple 统一一切，用 Constraint 持续检测，用 Process 自动修复。灵活、统一、规则驱动。
- **Heirloom 选择「实体 + 能力 + 状态机」**：用 Resource 封装语义，用 Abilities 强制安全边界，用状态机保证合法迁移。严格、可证明、类型驱动。

Heirloom 可以吸收 Open Ontology 在 triple 存储、规则引擎、DSL 体验上的优点，但应坚持 Resource/Abilities/State Machine/三级关系语义作为核心抽象。最终目标是：

> **Everything is a Resource at the semantic layer, and every change to a Resource is a typed, governed, auditable fact.**

---

## 7. 参考来源

- https://open-ontology.com/docs/concepts
- `/Users/nicholasl/Documents/build-whatever/heirloom/docs/core-concepts.md`
- Heirloom 白皮书 Part 1/2
- Heirloom ADR-001 ~ ADR-008
