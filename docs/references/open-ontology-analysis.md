# Open Ontology 深度分析

> 来源：open-ontology.com、ontologyruntime.com、ontology-db.com、metaform.systems
> 更新日期：2026-06-28

---

## 1. 项目概览

| 项目 | Open Ontology |
|------|--------------|
| **官网** | https://open-ontology.com/ |
| **相关域名** | open-ontology.com、ontologyruntime.com、ontology-db.com、metaform.systems |
| **定位** | "The programmable operations layer for enterprise agents" |
| **类型** | 宣称开源（具体 License 与 GitHub 仓库未在官网显著位置披露） |
| **实现语言** | **未公开披露**。面向用户的 DSL 是 Lisp（以及 TypeScript），但运行时/数据库本身的实现语言官网未说明。 |
| **成熟度** | Research preview（研究预览阶段） |
| **核心主张** | 把业务工作流本身变成基础设施，让 Agent 在受治理、可审计、可回放的 operational substrate 上运行。 |

Open Ontology 是迄今发现的与 Heirloom 思路最接近的项目之一。它不是聊天机器人、不是知识图谱供应商、也不是 LangChain 包装器，而是试图在企业数据层与 AI Agent 之间建立一个**可编程的操作语义层**。

> **重要区分**：名称相近但实质不同的项目至少有三个，阅读时需避免混淆：
> 1. **open-ontology.com / ontologyruntime.com / ontology-db.com**：本分析对象，面向企业 Agent 的可编程操作层，DSL 为 Lisp/TypeScript，运行时实现语言未公开。
> 2. **fabio-rovai/open-ontologies**（GitHub）：另一个也叫 "Open Ontologies" 的项目，用 **Rust** 实现的 AI-native ontology engineering MCP server，核心围绕 RDF/OWL/SPARQL，与本分析对象不是同一个项目。
> 3. **syzygyhack/open-foundry**（GitHub）：一个开源的 "operational digital twins" 本体平台，明确提到 semantic / kinetic / security 三层，技术栈为 Node.js/TypeScript + PostgreSQL+AGE + OpenFGA + CEL + GraphQL/REST，与 Heirloom 的架构惊人的接近，可能是 open-ontology.com 概念的实际落地或高度平行的项目。详见下文第 8 节。

---

## 2. 核心架构

Open Ontology 自底向上分为五层：

```
┌─────────────────────────────────────┐
│  Application 层                     │  Web、API、CLI、MCP
├─────────────────────────────────────┤
│  Runtime 层                         │  query → act → assert → explain
│                                     │  受治理的 Agent 操作、工作流执行
├─────────────────────────────────────┤
│  Compiler 层                        │  Lisp / TypeScript DSL → OntologyIR → Runtime payload
├─────────────────────────────────────┤
│  Language 层                        │ 业务上下文即代码（DSL）
├─────────────────────────────────────┤
│  Database 层                        │ 时间旅行三元组存储 [entity, attribute, value, timestamp]
└─────────────────────────────────────┘
```

### 2.1 Database：时间旅行三元组存储

- 数据模型：`[entity, attribute, value, timestamp]`
- 追加-only（append-only），撤销也作为新事实记录，不真正删除；
- 支持 point-in-time 查询（`as-of` 参数）；
- 无固定 schema，可随时给任意实体添加属性，无需迁移；
- 实体关系是一等公民，不是外键；
- 跨系统实体通过 `:_meta/same-as` 链接统一身份。

### 2.2 Language：业务上下文即代码

提供两套 DSL：

- **Lisp DSL**：适合 AI 生成、REPL 驱动开发；
- **TypeScript DSL**：适合 IDE 自动补全、编译时安全。

两者编译到同一中间表示（OntologyIR），可完美往返。

### 2.3 Compiler：Hindley-Milner 类型推断

- 基于 Algorithm W + 行多态（row polymorphism）+ CEL 桥接；
- 无需类型注解即可推断实体属性、Action 绑定、Datalog 查询结果形状；
- CEL 表达式在 Lisp 文档内被类型检查；
- 实体类型可组合继承（如 `:person` 扩展为 `:employee` 和 `:contractor`）。

### 2.4 Runtime：闭环检测与解决

Runtime 是「持续评估」的闭环：

```
Facts（三元组）
  ↓
Constraint Evaluation（Datalog 规则引擎）
  ↓
Violations（派生事实）
  ↓
Process Dispatch（DAG 编排）
  ↓
New Facts（回到起点）
```

- 约束不是写时检查，而是持续从当前事实库派生；
- 违规本身也是事实，可查询、有时间戳；
- Process 是 Action、Mutation、Query 组成的有向无环图；
- 每个状态转换都有可追溯的来源（外部断言或命名 Process 步骤）。

### 2.5 Application：多接口交付

- Web UI
- REST API
- CLI
- **MCP（Model Context Protocol）**：直接对接 Agent

---

## 3. 九种操作原语

| 原语 | 语法示例 | 对应 Heirloom 概念 | 说明 |
|------|---------|-------------------|------|
| **Entity** | `define-entity` | Resource Type | 有稳定身份和类型属性的业务实体 |
| **Relationship** | `define-relation` | Relationship | 带类型和时间语义的实体连接 |
| **Query** | `define-query` | Function / Query DSL | 保存的 Datalog 模式，用于确定性联接与推理 |
| **Mutation** | `define-mutation` | Action（mutate） | 带前置条件的状态变更操作 |
| **Action** | `define-action` | Action（含 Notification Action） | 受治理的工具调用，有类型边界 |
| **Process** | `define-process` | Automation（Action/Function 编排） | 多步骤工作流 DAG |
| **Constraint** | `define-constraint` | State / Validation Rules | 持续评估的声明式规则 |
| **View** | `define-view` | Workshop / UI | 查询绑定的声明式 UI |
| **Workspace** | `define-workspace` | Role/Perspective 视图 | 面向角色的仪表盘 |

### 3.1 代码示例

**实体定义**

```lisp
(define-entity Employee
  (:field [employee/name String {:required true}])
  (:field [employee/email String {:unique true}])
  (:field [employee/role String])
  (:field [employee/start-date Datetime]))
```

**约束定义**

```lisp
(define-constraint missing-certification
  (query
    (find [?emp ?role])
    (where
      [[?emp :employee/role ?role]
       [?role :role/requires-cert ?cert]
       (not [?emp :employee/cert ?cert])]))
  (severity error))
```

**Process 定义**

```lisp
(define-process resolve-missing-cert
  (trigger missing-certification)
  (steps
    [(define-action notify-manager
       (type email)
       (to (query (find [?mgr])
             (where [...]))))
     (form upload-certification
       (assignee ?emp)
       (fields [(:cert-file File)
                (:cert-date Datetime)]))
     (define-action record-certification
       (type assert)
       (facts [...]))]))
```

---

## 4. 与 Heirloom 的逐项对比

### 4.1 设计目标

| 维度 | Open Ontology | Heirloom |
|------|--------------|----------|
| **核心命题** | 把企业工作流变成可运行的 operational substrate | 为 AI Agent 提供类型安全的业务语义操作层 |
| **Agent 地位** | 一等消费者 | 一等消费者，与人类平权 |
| **数据哲学** | 三元组/事实为中心 | Resource 为中心 |
| **安全哲学** | 规则/约束/Process 显式化 | 类型层 Abilities 使非法操作不可表达 |

### 4.2 架构层对比

| Heirloom 层 | Open Ontology 对应 | 对比说明 |
|------------|-------------------|---------|
| Schema Registry | DSL + Compiler + OntologyIR | OO 用代码定义本体，Heirloom 用 Schema Registry；OO 强调版本控制与 CI/CD 集成 |
| Resource | Entity | 都是一等业务实体；OO 用 EAV 三元组，Heirloom 用 Resource（含 RID/Owner/State/Events/Version） |
| Relationship | Relation | OO 的关系带时间语义；Heirloom 强调 Ownership/Reference/Association 三级生命周期语义 |
| Abilities | Mutation/Action/Constraint 前置条件 | Heirloom 的 Abilities 是类型级强制；OO 的能力边界通过 Mutation 前置条件和 Process 触发条件表达，更像是「规则门禁」 |
| State Machine | Constraint + Process 闭环 | Heirloom 显式声明状态图；OO 通过持续约束检测和工作流响应实现类似效果，但状态机不是独立一级抽象 |
| Action | Action/Mutation/Process | OO 把状态变更拆成 Mutation 和 Process，Heirloom 统一为 Action；OO 的 Process 更接近 Heirloom 的 Automation |
| Function | Query | 都支持只读计算 |
| Capability/Role | Workspace + 权限范围 | Heirloom 的 Auth → Role → Capability → Action 链更完整；OO 的权限模型公开信息较少 |
| Event Log | Time-traveling triple store | 都强调不可变、可审计；OO 把审计内建在存储模型中，Heirloom 的 Event Log 是独立组件 |
| Mapping Engine | Connectors + Entity Linking | 都支持多源数据集成与实体链接 |
| Query Resolver | Datalog | Heirloom 用 LLM-friendly JSON DSL；OO 用 Datalog，对 LLM 生成可能不够友好 |
| Perspective Engine | Workspace/View | 都支持按角色裁剪视图 |

### 4.3 关键相似点

1. **Agent 是一等消费者**：两者都从第一天起为 Agent 设计，而非在现有系统上开后门。
2. **受治理的写入路径**：Agent 不直接写数据库，而是通过 Action/Mutation/Process。
3. **不可变审计**：每次操作都留下可追溯的事实/事件。
4. **业务上下文即代码**：本体定义可版本控制、可评审、可回滚。
5. **闭环系统**：规则/约束驱动操作，操作产生新状态，新状态再触发规则。
6. **跨系统集成**：都主张不 displacing 现有系统，而是作为统一语义层。

### 4.4 关键差异

| 差异点 | Open Ontology | Heirloom |
|--------|--------------|----------|
| **核心抽象** | 三元组/事实 + Datalog | Resource + Abilities + State Machine |
| **类型安全层级** | Hindley-Milner 类型推断 + CEL 规则 | 类型层 Abilities 强制（drop/mutate 等能力在 Resource Type 定义时声明） |
| **状态建模** | 通过约束和工作流隐式表达 | 显式状态机，状态图中不存在的迁移无法执行 |
| **关系语义** | Relation 带时间，但无 Ownership/Reference/Association 三级区分 | 精确三级语义，控制级联、权限传播、所有权转移 |
| **查询语言** | Datalog | JSON DSL（对 LLM 更友好） |
| **权限模型** | 公开信息较少，Workspace 做角色视图 | 完整的 Auth → Role → Capability → Action 链 |
| **数据模型** | EAV 三元组（schema-free） | Resource Store + Graph Store + Event Log（多模态存储） |
| **成熟度** | Research preview | 早期设计/实现阶段 |

### 4.5 一句话总结

> Open Ontology 和 Heirloom 都想在企业数据层与 AI Agent 之间建立一个**受治理、可审计、可回放的语义操作层**；Heirloom 更强调**类型级强制安全（Abilities）和显式状态机**，Open Ontology 更强调**规则驱动的持续约束检测和三元组时间旅行存储**。

---

## 5. 对 Heirloom 的启示

### 5.1 值得借鉴的设计

1. **时间旅行三元组存储**：把审计、历史、撤销统一在一个数据模型中，比独立 Event Log 更简洁。
2. **Hindley-Milner 类型推断 + 行多态**：对 DSL 体验是加分项，Heirloom 的 Resource Type 设计可考虑引入更强的类型推断。
3. **Constraint → Violation → Process 闭环**：把业务规则、异常检测、工作流编排自然连接起来，Heirloom 的 Automation 可参考此模式。
4. **DSL 双轨（Lisp + TypeScript）**：既服务 AI 生成，又服务开发者体验。
5. **MCP 原生接口**：直接让 Agent 通过 Model Context Protocol 接入，符合行业协议演进方向。
6. **Terraform-style deploy plan**：Schema 变更前先出 plan，再原子 apply，对治理友好。

### 5.2 Heirloom 可强化的差异化

1. **类型级 Abilities**：这是 Open Ontology 没有的。Heirloom 应继续把「非法操作不可表达」作为核心卖点。
2. **LLM-friendly 查询语言**：Open Ontology 用 Datalog，对 LLM 生成难度较高；Heirloom 的 JSON DSL 是优势。
3. **三级关系语义**：Ownership/Reference/Association 是 Heirloom 独有，可形成强差异化。
4. **Agent 与人类平权的统一校验链**：Heirloom 的 Role/Capability/Action 模型更完整，可作为安全卖点。
5. **元数据目录一体化**：Heirloom 自含 DataHub/OpenMetadata 级元数据能力，Open Ontology 未强调此层。

### 5.3 风险与不确定性

1. **开源状态待验证**：官网宣称开源，但未找到明确的 GitHub 仓库和 License；需持续观察其真实开放程度。
2. **成熟度低**：Research preview 阶段，可能尚未经过大规模企业生产验证。
3. **Datalog 的采纳门槛**：企业开发者和 LLM 对 Datalog 的熟悉度远低于 SQL/JSON。
4. **权限模型细节不明**：官网对 Auth/Role/Capability 的阐述较少，可能存在安全模型缺口。

---

## 6. 结论

Open Ontology 是 Heirloom 在开源/独立平台赛道上的**最接近参照**，也是最有力的潜在竞品。两者在「企业 Agent 需要受治理的语义操作层」这一判断上高度一致，但在实现路径上选择了不同的核心抽象：

- Open Ontology 选择 **事实/规则/工作流闭环**；
- Heirloom 选择 **Resource/Abilities/状态机/关系语义**。

Heirloom 应把 Open Ontology 作为**持续跟踪对象**，并在对外叙事中突出自己在**类型级安全、状态机可证明性、LLM-friendly 接口、元数据目录一体化**上的差异化优势。

---

## 7. 实现语言与相关项目辨析

### 7.1 Open Ontology 是用 Lisp 写的吗？

**不是整个系统都用 Lisp 写。**

- **用户可见的 DSL 是 Lisp**（以及 TypeScript）：官网展示的所有示例代码都是 S-expression 风格的 Lisp，例如 `(define-entity Employee ...)`。ontology-db.com 也提到 "Lisp for AI generation and REPL-driven development"，同时提供 TypeScript DSL 作为替代。
- **运行时/数据库实现语言未公开**：open-ontology.com、ontologyruntime.com、ontology-db.com 均未披露后端 runtime 或 triple store 是用什么语言实现的。三元组存储、Datalog 引擎、Hindley-Milner 类型引擎、部署管道等核心组件的实现语言目前不得而知。
- 因此准确的说法是：**Open Ontology 是一门用 Lisp/TypeScript 编写本体的平台，而非一个用 Lisp 实现全部后端的系统。**

### 7.2 容易混淆的同名/近名项目

| 项目 | 技术栈 | 与 open-ontology.com 的关系 | 关注点 |
|------|-------|---------------------------|--------|
| **fabio-rovai/open-ontologies** | **Rust** + Oxigraph + Tauri + React/TS | **不同项目**，只是名字相近 | AI-native ontology engineering MCP server，偏 OWL/RDF/SPARQL 工具链 |
| **syzygyhack/open-foundry** | **Node.js/TypeScript** + PostgreSQL+AGE + OpenFGA + CEL + GraphQL/REST | **高度相关或平行项目** | 开源 operational digital twins 平台，明确提到 semantic / kinetic / security 三层，架构与 Heirloom 非常接近 |

### 7.3 为什么 Open Foundry 也值得高度关注

Open Foundry（GitHub: syzygyhack/open-foundry）是一个开源的 "operational digital twins" 本体平台，它的描述与 Heirloom 的架构惊人地接近：

- **Semantic layer**：本体定义实体、关系、属性、约束；
- **Kinetic layer**：Action 流水线（authorize → consent → CEL → effects → audit）；
- **Security layer**：OpenFGA 实现 ReBAC，CEL 实现策略，Keycloak 实现 OIDC；
- 存储层用 **PostgreSQL + Apache AGE**（图扩展）；
- 事件总线用 Kafka（Redpanda），CDC 用 Debezium；
- 提供 GraphQL、REST、FHIR R4 接口；
- 强调不可变审计、字段级脱敏、consent 控制。

如果 Open Foundry 与 open-ontology.com 是同一团队或同一思路的落地实现，那么 Heirloom 面对的就不是一个还停留在官网概念阶段的项目，而是一个已经有具体代码栈和部署架构的竞品。即使两者无关，Open Foundry 也代表了「开源 + TypeScript/Node + PostgreSQL 图扩展 + OpenFGA」这一技术路线的竞争者，Heirloom 应将其纳入持续跟踪清单。

---

## 8. 参考来源

- https://open-ontology.com/
- https://ontologyruntime.com/
- https://ontology-db.com/
- https://metaform.systems/
- https://ontology.run/
- https://github.com/fabio-rovai/open-ontologies
- https://github.com/syzygyhack/open-foundry
