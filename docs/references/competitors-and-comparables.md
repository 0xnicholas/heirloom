# Heirloom 竞品与定位参照项目清单

> 范围：与 Heirloom（AI 原生语义本体平台，面向企业 AI Agent 的类型安全数据操作层）定位相近的商业与开源项目。
> 更新日期：2026-06-28

---

## 1. 筛选标准：用 Heirloom 的五个设计原则做对照

| 维度 | Heirloom 的核心主张 | 筛选时关注的问题 |
|------|-------------------|----------------|
| **Resource 一等实体** | 业务实体有独立 RID、生命周期、状态机，不只是数据源记录的投影 | 项目是否把「业务对象」作为核心抽象，而非仅做表/文档映射？ |
| **Abilities 类型层声明** | 能力（query/mutate/drop/transfer/freeze）在类型定义时声明，非外部 RBAC 配置 | 安全边界是在类型/模型层，还是靠管理员在另一系统配置权限？ |
| **状态机可证明** | 合法状态迁移在类型定义层声明，非法迁移无法表达 | 是否有原生状态机或工作流约束，还是靠 Action 代码手写检查？ |
| **关系语义决定生命周期** | Ownership / Reference / Association 三种语义精确控制级联与权限传播 | 关系是否有精确语义，还是只是无差别的边？ |
| **Agent 与人类平权** | Auth → Role → Capability → Action 统一校验链，Agent 不是二等消费者 | Agent 是否一等设计目标？是否有专门为 Agent 提供的安全操作路径？ |

---

## 2. 直接竞品总览

以下项目与 Heirloom 在「企业 AI Agent 的数据/操作语义层」这一赛道上重叠度最高，按**与 Heirloom 定位相似度**排序。

| 项目 | 类型 | 核心定位 | 与 Heirloom 的重叠点 | 关键差异 | 相关度 |
|------|------|---------|-------------------|---------|--------|
| **Palantir AIP / Foundry Ontology** | 商业 | 决策中心型企业语义本体 + AI Agent 平台 | 语义层、Object/Action、写入回源、审计、与人类共享安全策略 | Agent 是后加消费者；权限在 Sharing/RBAC 外部配置；关系是通用 Link Type | ⭐⭐⭐⭐⭐ |
| **DataWalk** | 商业 | "The Palantir Alternative"：统一知识图谱 + 无代码分析 | 知识图谱、本体管理、写入/分析、成本更低 | 更偏情报/调查分析，Agent 操作路径不如 Palantir/Heirloom 成熟 | ⭐⭐⭐⭐ |
| **Open Ontology** | 开源 | 面向企业 Agent 的可编程操作层 | 实体、关系、Action、Mutation、Process、Constraint、审计，与 Heirloom 思路几乎一致 | 用 Lisp DSL 和 Datalog，目前处于 research preview | ⭐⭐⭐⭐⭐ |
| **Open Foundry** | 开源 | Operational digital twins 本体平台（semantic / kinetic / security 三层） | Resource-like 实体、Action 流水线、OpenFGA/ReBAC、CEL、审计、PostgreSQL+AGE | 名称与 Open Ontology 无直接关联，但架构与 Heirloom 高度接近 | ⭐⭐⭐⭐⭐ |
| **Galaxy** | 商业 | 语义优先的 AI-ready 数据基础设施 | 本体驱动共享上下文、Agent grounding、血缘、审计、不迁移数据 | 更强调数据集成与语义建模，Action/写入能力公开信息较少 | ⭐⭐⭐⭐ |
| **TextQL** | 商业 | 基于本体的 Agentic 数据分析平台（Ana） | 本体知识图谱、自然语言查询、跨系统语义层、治理 | 偏分析/BI，写入/操作能力弱于 Heirloom | ⭐⭐⭐⭐ |
| **Glean** | 商业 | Work AI 平台：企业图谱 + Agent + 搜索 + 工作流 | Enterprise Graph、Agent Actions、权限感知、审计 | 以企业搜索/协作为入口，深度语义建模和类型级安全非核心卖点 | ⭐⭐⭐ |
| **Stardog** | 商业（社区版开源） | 企业知识图谱 + 虚拟图 + OWL 推理 | 本体、虚拟图、查询时推理、Agent grounding | 偏 RDF/SPARQL 语义网传统，无原生 Action/状态机/Abilities | ⭐⭐⭐ |
| **Kore.ai** | 商业 | 企业级对话/Agent 平台，带治理和工作流 | Agentic 工作流、企业搜索、多系统动作、治理 | 更偏对话/客服自动化，非 Resource-centric 语义层 | ⭐⭐⭐ |
| **Microsoft Fabric IQ / Copilot Studio** | 商业 | 微软生态的企业 Agent 与语义上下文层 | IQ 层连接多源结构化/非结构化数据、语义核心、Agent 工具 | 深度绑定微软生态，Agent 行为多由 Python 代码定义 | ⭐⭐⭐ |
| **Salesforce Agentforce / Atlas** | 商业 | CRM 生态的企业 Agent 平台 | Agent 可执行 CRM 操作、有 Trust Layer | 局限于 Salesforce 生态，非通用语义本体 | ⭐⭐ |

> 注：Heirloom 白皮书附录已覆盖 TypeDB、DataHub、Cube.js、SpiceDB、OpenMetadata 等单点参考项目，本清单重点补充**直接竞品/平台级参照**。

---

## 3. 重点项目详细分析

### 3.1 Palantir AIP / Foundry Ontology

- **官网/来源**：palantir.com/platforms/aip/、palantir.com/docs/foundry/ontology/why-ontology/
- **定位**：把企业数据、逻辑、行动和安全整合进一个决策中心型本体，人类与 AI Agent 共享同一 Operational Foundation。
- **与 Heirloom 的重叠**：
  - 语义层作为数据与应用的中间层；
  - Action Types 是唯一写入路径，附带验证、审批、审计；
  - Ontology Proposals 分支治理；
  - RID 全局唯一标识；
  - Agent 安全策略与人类 Sharing/RBAC 可共享。
- **与 Heirloom 的分歧**（白皮书第三部分已详细阐述）：
  - Agent 是后加消费者，AIP 叠加在 Ontology 之上；
  - 权限在 Sharing/RBAC 中外部配置，非类型契约；
  - Link Type 是通用边，无 Ownership/Reference/Association 三级语义；
  - 状态迁移依赖 Action 代码检查，非类型声明。
- **参考价值**：市场验证标杆。Heirloom 不是做开源 Palantir，而是证明「以 Agent 为首要消费者设计本体」能得出不同的架构选择。

### 3.2 DataWalk

- **官网/来源**：datawalk.com
- **定位**：自称是「Palantir Alternative」的端到端情报分析与知识图谱平台，强调更低成本、更快部署、无代码分析。
- **核心能力**：
  - 统一知识图谱（结合 RDF + LPG）；
  - 本体创建与管理；
  - 可视化查询、链接分析、地理空间分析、机器学习；
  - App Center 支持自定义应用与 LLM 集成；
  - 价格约为 Palantir Gotham 的 1/3（约 $43K/核心 vs $141K/核心）。
- **与 Heirloom 的重叠**：
  - 企业级知识图谱 + 本体管理；
  - 可连接多源数据并写入/回源；
  - 强调客户拥有数据与算法透明。
- **关键差异**：
  - 主战场是情报、反洗钱、欺诈调查，Agent 自主操作不是首要设计目标；
  - 无明确的「类型层 Abilities + Capability 链」安全模型；
  - 关系语义不如 Heirloom 精细。
- **参考价值**：Palantir 的低价替代路径，证明中型企业愿意为「简化版本体+分析」买单。

### 3.3 Open Ontology

- **官网/来源**：open-ontology.com、ontology-db.com、ontology.run
- **定位**："The programmable operations layer for enterprise agents" —— 把业务工作流变成可运行、可审计、可回放的 operational substrate。
- **核心原语**：Entities、Relationships、Queries、Mutations、Actions、Processes、Constraints、Views、Workspaces。
- **与 Heirloom 的重叠**（极高）：
  - 实体有稳定身份和类型属性；
  - 关系带类型和时间；
  - Mutation 是显式状态变更操作；
  - Action 是受治理的工具调用；
  - Constraint 是声明式规则，持续检测违规；
  - 时间旅行三元存储，每个事实带时间戳，Action 写入审计日志；
  - Agent 与人类通过同一运行时操作。
- **关键差异**：
  - 使用 Lisp DSL 和 Datalog，而非 Heirloom 的 Resource/Abilities/State Machine 模型；
  - 目前处于 research preview，成熟度未知；
  - 状态机/Abilities 的抽象不如 Heirloom 显式。
- **参考价值**：**最像 Heirloom 的开源项目**。二者都主张「业务上下文即基础设施」，可作为开源生态位的重要参照。

### 3.4 Open Foundry

- **官网/来源**：https://github.com/syzygyhack/open-foundry
- **定位**：开源的 "operational digital twins" 本体平台，明确分为 semantic / kinetic / security 三层。
- **核心能力**：
  - **Semantic layer**：本体定义实体、关系、属性、约束；
  - **Kinetic layer**：Action 流水线 `authorize → consent → CEL → effects → audit`；
  - **Security layer**：OpenFGA 实现 ReBAC，CEL 实现策略，Keycloak 实现 OIDC；
  - 存储层用 **PostgreSQL + Apache AGE**（图扩展）；
  - 事件总线用 Kafka（Redpanda），CDC 用 Debezium；
  - 提供 GraphQL、REST、FHIR R4 接口；
  - 强调不可变审计、字段级脱敏、consent 控制；
  - 技术栈：Node.js/TypeScript + pnpm monorepo，约 20 个 packages。
- **与 Heirloom 的重叠**（极高）：
  - 同样是 Resource-like 实体 + Action 的操作模型；
  - 同样把权限、审计、动作编排作为核心；
  - 同样使用 CEL 做策略表达式（Heirloom 可考虑借鉴）；
  - 同样强调 Agent/自动化工作流与人类共享治理层；
  - 多模态存储（关系+图）与 Heirloom 的 Resource Store + Graph Store 思路一致。
- **关键差异**：
  - 名称与 Open Ontology 无直接关联，但两者在「可编程操作层」思想上高度接近；
  - 使用 PostgreSQL+AGE 而非独立 triple store；
  - 生态相对早期，需要 Docker Compose 完整启动；
  - 无 Heirloom 那样显式的 Abilities/State Machine/三级关系语义抽象。
- **参考价值**：**Heirloom 在技术栈和架构上最直接的代码级参照**。如果 Open Foundry 与 Open Ontology 是同一思路的落地，那么 Heirloom 面对的是一个已经有具体实现的开源竞品。

### 3.5 Galaxy

- **官网/来源**：getgalaxy.io
- **定位**：自动化数据与 AI 基础设施平台，构建本体驱动的语义模型和共享上下文层，服务人类分析与 AI Agent。
- **核心能力**：
  - 本体驱动的共享上下文；
  - 非侵入式连接现有系统（不强制迁移数据）；
  - 血缘感知分析与审计；
  - AI Agent grounding；
  - 业务工作流的生命周期与状态建模。
- **与 Heirloom 的重叠**：
  - 强调「语义层 + AI 消费」；
  - 治理、血缘、审计；
  - 不 displacing 现有工具。
- **关键差异**：
  - 公开资料更多强调「语义集成与分析」，Action/写入路径的深度弱于 Heirloom；
  - 早期阶段平台，市场验证有限。
- **参考价值**：同样是「Agent 原生语义基础设施」这一新兴品类的竞争者，可帮助 Heirloom 明确差异化叙事。

### 3.6 TextQL

- **官网/来源**：textql.com
- **定位**：面向大型企业的 Agentic 数据分析平台，AI 分析师 Ana 通过本体支持的语义层回答自然语言问题。
- **核心能力**：
  - 本体构建器（Ontology Builder）手动定义对象、属性、关系；
  - Ana 自动转 SQL、生成报告、执行数据科学 notebook；
  - 跨系统查询（BI、数据仓库、dbt、CRM 等）；
  - 行/列级访问控制与审计日志；
  - 医疗、金融、保险等行业垂直语义。
- **与 Heirloom 的重叠**：
  - 本体知识图谱作为 Agent 理解数据的基础；
  - 语义层归数据团队所有，防止指标漂移；
  - 强调治理和可审计。
- **关键差异**：
  - 主要是**只读分析 Agent**，写入/业务操作能力弱；
  - 需要大量前期本体配置，部署周期数周至数月；
  - 商业闭源，存在 vendor lock-in 争议。
- **参考价值**：证明「本体 + NL Analytics」是企业愿意付费的方向，但 Heirloom 可强调自己是「操作层」而非「分析层」。

### 3.7 Glean

- **官网/来源**：glean.com
- **定位**：Work AI 平台，连接企业数据、系统和上下文，提供搜索、助手和 Agent。
- **核心能力**：
  - Enterprise Graph（人、内容、活动、权限映射）；
  - Glean Agents 可执行多步骤工作流；
  - 权限感知搜索与 AI 治理；
  - 与 Slack、Salesforce、Jira、Snowflake 等深度集成；
  - 已披露 2 亿美元 ARR。
- **与 Heirloom 的重叠**：
  - 企业图谱作为 Agent 上下文；
  - Agent 可执行动作；
  - 强调安全、权限、审计。
- **关键差异**：
  - 入口是「企业搜索/知识发现」，语义建模深度有限；
  - 不做通用 Resource/State Machine/Abilities 抽象；
  - 连接器生态相对封闭。
- **参考价值**：展示「企业图谱 + Agent Actions」的市场需求，但 Heirloom 可在「类型级安全」和「状态机」上形成差异。

### 3.8 Stardog

- **官网/来源**：stardog.com
- **定位**：RDF/SPARQL 原生企业知识图谱平台，支持 OWL 推理和虚拟图。
- **核心能力**：
  - W3C 标准本体（OWL/RDFS/SHACL）；
  - 虚拟图技术，查询时跨源推理；
  - Voicebox（GraphRAG 产品）用于 LLM grounding；
  - 社区版免费，商业版企业级支持。
- **与 Heirloom 的重叠**：
  - 强类型 schema-first 建模；
  - 虚拟化语义层，Agent 无需关心底层数据源；
  - 推理引擎保证查询正确性。
- **关键差异**：
  - 无原生 Action/Mutation 概念，写入路径留给应用层；
  - 无 Abilities/Capability 类型层安全模型；
  - RDF/SPARQL 学习曲线陡峭。
- **参考价值**：Heirloom 白皮书已将 TypeDB 作为「类型系统参考」；Stardog 可作为「RDF/虚拟图/推理」路线的补充参照。

### 3.9 Microsoft Fabric IQ / Copilot Studio

- **来源**：Microsoft Learn、软件评论报告（2026-05）
- **定位**：微软生态的企业 Agent 与语义上下文层。Fabric IQ 连接所有结构化/非结构化源，通过本体和业务上下文图提供共同语义。
- **核心能力**：
  - IQ 层作为跨源语义中枢；
  - Copilot Studio 构建 Agent；
  - Semantic Kernel 提供神经符号 Agent 框架；
  - Azure OpenAI 集成。
- **与 Heirloom 的重叠**：
  - 语义层 + Agent 操作；
  - 治理与审计；
  - 企业级部署。
- **关键差异**：
  - 深度绑定微软生态；
  - Agent 行为目前仍多在 Python 代码中定义，非类型系统级约束；
  - 定位是生态增强，不是独立语义本体产品。
- **参考价值**： hyperscaler 正在把「本体/语义层」作为 Agent 基础设施，验证 Heirloom 方向，但也预示激烈竞争。

### 3.10 Salesforce Agentforce / Atlas

- **来源**：arxiv 论文《Declarative Orchestration of Enterprise Knowledge for Agentic AI Systems》（2025-11）
- **定位**：CRM 生态的企业 Agent 平台，Atlas 引擎提供「System 2」推理和协作 Agent swarm。
- **与 Heirloom 的重叠**：
  - Agent 可在 CRM 内执行操作；
  - Einstein Trust Layer 提供治理。
- **关键差异**：
  - 局限于 Salesforce 生态；
  - 非通用 Resource/语义本体层。
- **参考价值**：说明主流 SaaS 厂商都在把 Agent 操作嵌入业务系统，Heirloom 的机会在于「跨系统的通用语义层」。

### 3.11 ServiceNow + data.world

- **来源**：软件评论报告（2026-05）
- **定位**：ServiceNow 收购 data.world 后构建企业 Agent 平台，利用其本体和知识图谱提供共同语义。
- **与 Heirloom 的重叠**：
  - 通过本体提供 Agent 可理解的业务语义；
  - 与 ITSM/工作流深度集成。
- **关键差异**：
  - 起点是 IT 服务管理/工作流；
  - 通用性不如 Heirloom。
- **参考价值**：又一个「本体 + Agent」的战略级收购，证明该品类价值被大厂认可。

---

## 4. 开源项目补充

以下开源项目在单点或多点上与 Heirloom 相关，可作为架构实现或生态集成的参考。

| 项目 | 定位 | 与 Heirloom 的关系 |
|------|------|------------------|
| **TypeDB** | 强类型图数据库 | 类型系统与 schema-first 建模参考（白皮书已覆盖） |
| **Open Ontology** | 企业 Agent 可编程操作层 | **最直接的开源竞品/参照**，见 3.3 |
| **Open Foundry** | Operational digital twins 本体平台 | **代码级最接近参照**，见 3.4；Node.js/TS + PostgreSQL+AGE + OpenFGA + CEL |
| **DataHub** | 元数据目录与数据治理 | 元数据层参考（白皮书已覆盖） |
| **OpenMetadata** | 开源元数据目录 | 与 DataHub 类似，可对比元数据模型设计 |
| **SpiceDB / OpenFGA / Oso / Cedar** | 细粒度权限/授权 | Capability/权限模型参考（白皮书已覆盖 SpiceDB） |
| **dbt Semantic Layer / MetricFlow** | 语义层/指标层 | 指标定义与治理参考，但缺少 Resource/Action 抽象 |
| **Ontop** | 虚拟知识图谱 | 关系型数据虚拟化为 RDF/SPARQL，可作 Mapping Engine 参考 |
| **LinkML** | 数据建模语言 | 可借鉴其 schema/ontology 建模语言设计 |
| **LangGraph / AutoGen / CrewAI / Dify / Agno** | Agent 编排框架 | Agent 侧工具调用、记忆、多 Agent 协作参考，但无企业数据层 |
| **GraphRAG (Microsoft)** | 知识图谱 + RAG | 非结构化数据 grounding 参考 |

---

## 5. 战略洞察

1. **品类正在成型**：Palantir、Microsoft、Salesforce、ServiceNow、Snowflake、Teradata 等都在 2025–2026 年把「本体/语义层 + Agent 操作」作为核心战略方向。Heirloom 不是在一个空白市场讲故事，而是在一个被大厂验证、但尚未有开源/独立平台统治的赛道。

2. **Heirloom 的差异化锚点**：
   - **Agent 是一等消费者**（vs Palantir 的后加消费者、Microsoft 的代码定义 Agent）；
   - **类型层 Abilities** 使非法操作不可表达（vs 外部 RBAC/Trust Layer）；
   - **Ownership/Reference/Association 三级关系语义**（vs 通用边/链接）；
   - **统一 Auth → Role → Capability → Action 校验链**，Agent 与人类平权。

3. **最值得跟踪的竞品**：
   - **商业**：Palantir AIP（标杆）、DataWalk（低价替代）、TextQL（分析 Agent）、Glean（工作图谱）、Galaxy（语义基础设施）。
   - **开源**：**Open Ontology** 是最接近 Heirloom 思路的项目；**Open Foundry** 是代码级最接近的已开源实现，两者都应持续跟踪。

4. **机会窗口**：大厂方案多为生态锁定、Agent 行为依赖代码配置；开源方案多为单点工具。Heirloom 的机会在于提供一个**独立、类型安全、Agent 原生**的语义本体平台，填补「开源 Open Ontology 不成熟、商业 Palantir 锁定且昂贵」之间的空白。

---

## 6. 参考来源

- Palantir 官方文档与 2025 财报/10-K
- DataWalk 官网及第三方分析（datawalk.com, techbullion.com, valliance.ai）
- Open Ontology 官网（open-ontology.com, ontology-db.com, ontology.run）
- Open Foundry GitHub（syzygyhack/open-foundry）
- Open Ontologies (Rust MCP) GitHub（fabio-rovai/open-ontologies）
- Galaxy 官网与评测（getgalaxy.io）
- TextQL 官网、PRNewswire、zenlytic.com 对比分析
- Glean 官网与 Forrester Wave / Kore.ai 评测
- Stardog 官网与社区文档
- Microsoft Fabric / Copilot Studio / Semantic Kernel 公开资料
- arXiv 论文《Declarative Orchestration of Enterprise Knowledge for Agentic AI Systems》
- 软件评论报告《Snowflake Announces Expansion of Snowflake Intelligence and Cortex Code》（2026-05-29）
