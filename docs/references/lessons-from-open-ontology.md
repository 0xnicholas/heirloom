# 从 Open Ontology 借鉴的设计要点

> 本文档从 Open Ontology 的设计中提取对 Heirloom 有借鉴价值的点，并说明每个点的 **Why**——即它解决什么问题、为什么对 Heirloom 有价值。
> 来源：open-ontology.com、ontologyruntime.com、ontology-db.com
> 更新日期：2026-06-28

---

## 借鉴点 1：用统一的事实模型承载审计、历史与撤销

### Open Ontology 的做法

Open Ontology 把所有数据建模为带时间戳的三元组：

```
[entity, attribute, value, timestamp]
```

- 新增事实是 `assert`；
- 修改事实是追加一条新事实，旧事实标记为 retracted；
- 删除事实也是追加一条撤销记录；
- 审计、历史、撤销都内建在同一个数据模型中。

### Why：它解决什么问题？

1. **审计不再是附加组件**：传统系统需要独立的审计表、CDC 日志、操作日志。Open Ontology 把这些统一在 triple 模型里，降低了架构复杂度。
2. **Point-in-time 查询是原生的**：可以问「这个实体在任意历史时刻的状态是什么」，而不需要拼装多个快照表。
3. **撤销/修正不会抹除历史**：当 Agent 犯错被纠正时，修正本身成为新事实，而不是把旧记录覆盖掉。这对 Agent 行为的可解释性至关重要。
4. **单一数据模型降低认知负担**：开发者不需要理解「业务表 + 审计表 + 事件表」三套抽象。

### Heirloom 如何应用

Heirloom 已经有机独立组件（Resource Store、Event Log、Graph Store）。可以借鉴的是：

- **把 Event Log 规范化为 typed fact 序列**：每个事件可以表示为 `[rid, attribute, value, timestamp, actor, role, action]`；
- **让 Resource 的当前状态是 Event Log 的物化视图**：这样 point-in-time 查询可以通过重放事件实现；
- **保留 Resource 作为语义抽象**：底层可以用 triple/fact 表示，但对外仍暴露 Resource 的强类型接口。

### 风险与注意事项

- 不要把「Resource 是一等抽象」退化为「Triple 是一等抽象」，否则会弱化 Abilities 和状态机；
- Triple store 的写入和查询性能需要评估，特别是高频 Action 场景；
- 需要明确 fact 的事件 schema，否则不同 Action 产生的事件格式会碎片化。

---

## 借鉴点 2：规则引擎作为 Action 校验的补充

### Open Ontology 的做法

Open Ontology 把 **Constraint** 作为一等原语：

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

- Constraint 是 Datalog 查询，持续在事实库上运行；
- 当查询返回结果时，系统生成 Violation；
- Violation 触发 Process，Process 编排 Action/Mutation 来修复问题。

### Why：它解决什么问题？

1. **跨实体的业务规则**：很多业务规则不是单个 Action 能检查的，例如「每个活跃供应商必须有有效证书」涉及 Vendor、Certificate、时间三个维度。
2. **规则与操作分离**：业务规则写在 Constraint 里，而不是散落在各个 Action 的 Validate 代码中，避免遗漏。
3. **闭环自动化**：规则触发 → 任务分配 → Agent/人工处理 → 修复后规则自动解除，形成自纠正系统。
4. **Agent 的行为边界更清晰**：Agent 不仅被 Action 校验约束，还被全局规则约束。

### Heirloom 如何应用

Heirloom 当前主要靠 Action 的 Validate 步骤执行业务规则。可以补充：

- **引入 `Constraint` 作为语义原语**：用声明式规则表达跨 Resource 的不变式；
- **Constraint 在两种场景生效**：
  - **定义时**：约束 Action 的注册（如果 Action 会导致约束永久违反，则拒绝注册）；
  - **运行时**：持续检测已存在实例的违规状态，生成告警或 Automation 触发器；
- **与状态机互补**：状态机管「状态迁移是否合法」，Constraint 管「全局状态是否一致」。

### 风险与注意事项

- 不要把 Constraint 替代 Abilities。Constraint 是「规则检查」，Abilities 是「能力边界」，两者层级不同；
- Datalog 的学习曲线高，Heirloom 可以考虑用更友好的规则语法（如 CEL 或类 SQL）；
- 持续评估 Constraint 的性能开销需要控制，避免全量扫描拖垮系统。

---

## 借鉴点 3：Schema-free 的轻量扩展字段

### Open Ontology 的做法

Open Ontology 的 triple store 没有固定 schema：

- 可以随时给任何 Entity 添加新属性，无需迁移；
- 实体类型定义约束「通常应该有什么属性」，但底层存储不强制；
- 这种灵活性适合快速演化的业务场景。

### Why：它解决什么问题？

1. **业务演化的摩擦降低**：新字段不需要 DDL 变更和部署；
- **Agent 发现的属性可被快速沉淀**：Agent 从非结构化数据中提取出新属性时，可以直接写入而不用先改 schema；
- **减少 schema 变更的阻塞**：某些字段只在特定场景使用，不值得走完整的 Proposal 流程。

### Heirloom 如何应用

Heirloom 的 Resource Type 是强类型的。可以引入**两层字段模型**：

- **Schema 字段**：在 Resource Type 中声明的核心字段，受 Abilities 和 Perspective Engine 严格控制；
- **扩展字段（extension fields）**：运行时附加的键值对，存储在 Resource Store 中但默认不参与核心逻辑；
- **扩展字段的治理**：
  - 可被 Perspective Engine 控制可见性；
  - 可被 Discovery Engine 自动识别并提议升级为 schema 字段；
  - 不影响 Abilities 和状态机。

### 风险与注意事项

- 扩展字段不能成为安全绕过的通道——它们必须受 Perspective Engine 和 Capability 链约束；
- 需要防止扩展字段无限增长导致存储和索引膨胀；
- Agent 不应依赖未声明的扩展字段执行关键业务逻辑。

---

## 借鉴点 4：Lisp/TypeScript DSL 作为可选建模语言

### Open Ontology 的做法

Open Ontology 提供两套 DSL：

- **Lisp DSL**：适合 AI 生成、REPL 驱动、token 高效；
- **TypeScript DSL**：适合 IDE 自动补全、编译时类型安全。

两者编译到同一中间表示（OntologyIR），可以互相转换。

### Why：它解决什么问题？

1. **AI 生成友好**：Lisp 的 homoiconic 结构让 LLM 生成时语法错误率低于 JSON/YAML；
2. **开发者体验**：TypeScript DSL 提供 IDE 支持、类型检查、自动补全；
3. **不同用户群体的偏好**：数据工程师可能喜欢 JSON/YAML，本体工程师可能喜欢 Lisp/TS；
4. **版本控制友好**：文本 DSL 适合 git diff 和 code review。

### Heirloom 如何应用

Heirloom 当前以 Schema Registry（可能是 JSON/YAML/数据库模型）为核心。可以：

- **保持 Schema Registry 的内部模型不变**；
- **新增 Lisp DSL 作为可选输入格式**：
  ```lisp
  (define-resource-type Customer
    (abilities [query mutate transfer freeze])
    (fields
      [(name String {:required true})
       (tier String)]))
  ```
- **新增 TypeScript DSL 作为企业开发者友好选项**；
- **所有 DSL 都编译到同一 Schema Registry 内部模型**。

### 风险与注意事项

- DSL 是「输入格式」选项，不应导致内部模型分裂；
- 需要维护 DSL → 内部模型 → DSL 的往返能力，否则会造成 lock-in；
- Lisp 的企业采纳门槛高，应作为「高级选项」而非默认选项。

---

## 借鉴点 5：Terraform-style Schema 部署计划

### Open Ontology 的做法

Open Ontology 的部署流程：

```bash
$ ontology deploy --plan

Ontology: staffing-compliance
Database: production

Changes:
  + entity :contractor             (3 fields)
  ~ entity :employee               (add :clearance-level)
  + constraint  :clearance-required
  + process    :obtain-clearance

0 removals, 1 modification, 3 additions

Run 'ontology deploy --apply' to apply.
```

- 先 plan，展示所有变更；
- 确认后再 apply；
- 部署是原子的，要么全部成功，要么全部回滚。

### Why：它解决什么问题？

1. **Schema 变更的可预测性**：在生产环境执行前，先看到精确影响范围；
2. **Code Review 的数据基础**：PR 中可以附带 plan 结果，评审者能直观理解变更；
3. **原子性降低风险**：避免部分 schema 变更成功、部分失败导致的中间状态；
4. **与 git 工作流集成**：每次 ontology 变更都是 commit + PR + plan + apply。

### Heirloom 如何应用

Heirloom 已经有 Proposal → Branch → Review → Merge 的治理流程。可以补充：

- **Plan 阶段**：在合并前生成 schema diff 和执行计划；
- **Plan 内容**：新增/修改/删除的 Resource Type、Ability、State Machine、Relationship、Action；
- **影响分析**：显示哪些现有 Resource 实例会受影响（如字段删除、状态机变更）；
- **原子 Apply**：部署时要么全成功，要么全回滚；
- **Rollback**：通过 git revert + 重新部署回退。

### 风险与注意事项

- Plan 的准确性依赖 Schema Registry 的状态快照，需要保证快照与生产一致；
- 某些 schema 变更（如删除字段）可能涉及数据迁移，plan 需要包含数据层影响；
- 原子 apply 对存储层的事务能力有要求。

---

## 借鉴点 6：Agent 操作的「query → act → assert → explain」闭环

### Open Ontology 的做法

Open Ontology 的 Runtime 口号是：

```
query → act → assert → explain
```

- Agent 先查询上下文；
- 再执行 Action；
- 把结果断言为新事实；
- 最后解释做了什么、为什么做。

### Why：它解决什么问题？

1. **可解释性**：每个 Agent 操作都能解释其依据（查询到了什么、触发了什么规则）；
2. **可审计性**：query、act、assert 都留下痕迹，便于事后复盘；
3. **Agent 学习与校正**：失败的 query/act 可以成为优化 Agent Role 和训练数据的输入；
4. **与人类协作透明**：人类审核者能看到 Agent 的完整推理链。

### Heirloom 如何应用

Heirloom 的 Action 九步流水线已经记录了事件。可以进一步强化：

- **Event 中记录 query 上下文**：Action 执行前 Agent 查询到了哪些 Resource、哪些字段；
- **Event 中记录决策依据**：为什么调用这个 Action（例如「因为 Constraint X 触发」或「因为用户请求」）；
- **Explain API**：给定一个 Action Event，返回其完整上下文链；
- **失败也记录完整上下文**：被拒绝的操作不仅记录原因，还记录调用者的 query 上下文。

### 风险与注意事项

- 记录完整 query 上下文可能增加 Event Log 体积；
- 需要定义「explain」的输出格式，避免信息过载；
- 某些 query 上下文可能包含敏感字段，需要受 Perspective Engine 控制。

---

## 总结

| 借鉴点 | 解决的核心问题 | Heirloom 的应用层级 |
|--------|--------------|-------------------|
| 统一事实模型 | 审计/历史/撤销碎片化 | 存储层 / Event Log |
| 规则引擎 | 跨实体业务规则难以表达 | 语义原语 / Action Validate |
| Schema-free 扩展字段 | 业务演化 friction | Resource Type 字段模型 |
| Lisp/TS DSL | 建模语言对 AI/开发者不友好 | Schema Registry 输入层 |
| Terraform-style 部署 | Schema 变更不可预测 | 治理 / CI-CD |
| query→act→assert→explain | Agent 操作不可解释 | Event Log / API |

这些借鉴点都不应动摇 Heirloom 的核心差异化：**Resource 抽象、类型层 Abilities、显式状态机、三级关系语义**。它们是在此基础上的增强，而非替代。
