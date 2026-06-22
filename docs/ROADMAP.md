# Heirloom Roadmap

本路线图基于白皮书附录 C 的四阶段成熟度模型展开，将系统能力的建设分解为五个工程阶段。时间线为方向性指引，非承诺交付日期。

> **最近更新（v0.2，2026-06-22）**：根据代码现状批量同步勾选状态。Phase 0–3 大部分核心子项已完成，Phase 4–5 待启动。

---

## Phase 0: 核心基础设施（Core Infrastructure）

**目标**：搭建最小可用的类型系统和存储基础。能够定义 Resource Type 并执行基本的属性读写。

**预计周期**：6-8 周

### 0.1 Schema Registry（引导版）

- [x] 实现 Resource Type 定义 API：创建、查询、更新类型（字段定义、基本元数据）
- [x] 内置最小引导 Schema——Schema Registry 自身类型的硬编码定义，解决自举问题
- [x] Schema 版本化：每次类型变更产生新版本号，保留旧版本用于历史查询兼容
- [x] Schema 校验：在类型定义时检查字段类型一致性、引用完整性

### 0.2 Resource Store

- [ ] 基于 PostgreSQL JSONB 的 Resource 实例存储
- [ ] 按 RID 点查、按类型批量扫、按属性值过滤
- [ ] 乐观锁（version 字段）支持并发写入安全
- [ ] 基础索引（RID、type、高频过滤字段）

> **注**：Phase 0.2 暂被 descope。设计重心已从「自有 Resource 实例存储」转向「对外部数据源的语义映射查询」（见 ADR-003 存储层分离 + ADR-018 Discovery as Entity）。Resource 实例 CRUD 由外部数据源（PostgreSQL / REST API）承担，Heirloom 仅做语义层。如未来需要自有存储则重启此阶段。

### 0.3 基础消费 API

- [x] REST API：Resource 创建、读取、更新（通过 TypeResource + KnowledgeResource + DiscoveryResource；不含 0.2 意义上的 instance CRUD）
- [ ] 基础 GraphQL 端点（可选）

### 0.4a 知识库核心管线（Knowledge Core Pipeline）

**设计前提**：知识的主要生产方式来自文件——数据团队在 `knowledge/` 目录中编写 Markdown。Heirloom 的角色是**发现、索引、搜索、服务**这些文件，而非编辑它们。

- [x] `KnowledgeSource` Entity + Repository + Resource — 注册文件目录配置
- [x] `KnowledgeArticle` Entity + Repository + Resource — 只读查询 API
- [x] `KnowledgeSyncEngine` + `KnowledgeSyncService` — 扫描→diff→parse→upsert
- [x] `FileScanner` — 目录遍历 + SHA-256 hash
- [x] `FrontmatterParser` — YAML 解析 + type 校验 + 标签规范化
- [x] `SyncDiff` / `SyncReport` — 增量 diff 计算 + 同步结果报告
- [x] 数据库迁移 V3：`knowledge_sources` + `knowledge_articles` 表
- [x] 手动触发同步：`POST /v1/knowledge/sources/{id}/sync`
- [x] Unit tests: 5 个测试类（Source, Article, Parser, Scanner, Engine）
- [x] Integration test: 端到端同步 + 审计验证
- [x] Spec: [Phase 0.5a 实现规格](../docs/superpowers/specs/2026-06-22-knowledge-base-phase-0.5a.md)
- [x] 设计参考：[ADR-032](../docs/adr/032-knowledge-base-architecture.md)

**退出标准**：能够通过 API 注册知识源、触发同步、查询已索引的知识条目。增量同步正确（未变更文件跳过）。

### 0.4b 知识库增强（Knowledge Enhancement）

- [x] 管道 B（元数据自举）：Discovery 完成后自动生成 .md 草稿
- [x] Mustache 模板引擎 + `table-knowledge.mustache` 默认模板
- [x] `_generated/discovery/` 目录
- [x] `index.md` 自动生成器（OKF §6）
- [x] `log.md` 变更日志生成器（OKF §7）
- [x] frontmatter `resource` → Heirloom FQN 自动解析
- [x] `status` 字段 + 状态机（draft→review→published→archived）
- [x] 图分析缓存字段（incomingRefCount 等）
- [x] 设计参考：[ADR-033 转换管道](../docs/adr/033-knowledge-conversion-pipeline.md)

**退出标准**：Discovery 扫描后自动生成知识草稿；index.md/log.md 自动维护；文件目录完全 OKF 自足。

---

## Phase 1: 语义查询层（Semantic Query Layer）— MVP

**目标**：实现成熟度模型阶段一——统一的只读语义查询层。验证「多源数据 → 统一语义查询」的核心价值。

**预计周期**：8-10 周

### 1.1 Mapping Engine

- [x] 连接器框架：定义 Connector 抽象接口（连接、查询、流式读取、元数据发现）
- [x] DB Connector：PostgreSQL（Discovery via SchemaExtractor 全量加载；增量同步尚为 placeholder）
- [ ] DB Connector：CDC 增量同步 via WAL / Debezium
- [ ] API Connector：REST API（定时轮询 + Webhook 接收）
- [x] 映射规则 DSL：定义「业务字段 → 物理数据源」的映射（数据源、查询模板、同步策略）
- [x] 映射规则版本化与校验

### 1.2 Query Resolver

- [x] JSON DSL 解析器与校验器
- [x] 查询计划生成：将语义查询翻译为多源查询计划（分派到底层存储）
- [x] 基础操作：过滤（`$eq`, `$gt`, `$in`, `$and`, `$or`）、排序、分页
- [x] 关系遍历：单步遍历（`c --[placed]--> Order`），内联结果合并
- [x] 聚合：`$count`、`$sum`、`$avg`、`$max`、`$min`，支持 `group_by`
- [ ] 查询计划优化：合并冗余查询、选择最优执行顺序

### 1.3 Perspective Engine（基础版）

- [ ] 字段级可见性配置：在 Resource Type 字段上标注「对哪些 Role 可见」
- [ ] 查询计划阶段注入字段过滤（非 API 层后处理）
- [ ] 缓存：Role → 可见字段映射表缓存

> **注**：知识库侧已有 `KnowledgePerspectiveFilter`（按 domain + type + status 过滤），但通用 Resource Type 的字段级 Perspective Engine 尚未实现。`KnowledgeCapability` 已在 Phase 2.3 中规划。

### 1.4 Event Log（基础版）

- [x] 不可变追加存储（PostgreSQL 分区表 — `change_events` 表 + `EventLogRepository`）
- [x] 时间范围查询
- [x] 事件 Schema：`{ event_type, rid, timestamp, caller, ... }`

### 1.5 知识搜索与引用（Knowledge Search & References）

- [x] PostgreSQL 全文检索搜索端点（`/v1/knowledge/search?q=...`）
- [x] 引用解析：同步时自动解析 Markdown 链接 → `EntityReference`（若目标 FQN 在 EntityRegistry 中存在）
- [x] 反向引用查询（`?ref={fqn}`）
- [x] Git webhook 触发自动同步（`on-commit` 模式）
- [x] OKF 导出端点——从文件系统直接打包 tar.gz
- [x] 管道 A（外部导入）：`HtmlImporter` + `ConfluenceImporter`（实际为 `KnowledgeImporter` 抽象 + `HtmlImporter`）
- [x] 管道 B 增强：`update_metadata_blocks` 增量策略（仅更新 Schema 区块）
- [x] 图遍历 API（`/v1/knowledge/graph/traverse`、`/coverage`、`/impact`）
- [x] 质量评分引擎 + API（`/v1/knowledge/quality/report`）

**退出标准**：可以通过 JSON DSL 查询跨 PostgreSQL + REST API 的数据，字段可见性受 Role 配置控制。所有操作记录到 Event Log。知识条目支持全文搜索、引用解析、webhook 自动同步、图遍历和质量分析。

---

## Phase 2: 安全操作层（Secure Operations）— 生产可用

**目标**：实现成熟度模型阶段二——通过 Action 层引入安全写入，完整的 Abilities + Role + Capability 模型上线。

**预计周期**：10-12 周

### 2.1 Abilities 系统

- [x] Abilities 声明：在 Resource Type 定义时声明 8 种能力标记
- [x] 编译时校验：Action 定义时检查 target 类型是否声明了 `requires` 的 Ability（ADR-002, ADR-007）
- [ ] Ability 变更需 Proposal 流程

### 2.2 Action 引擎

- [x] 九步校验流水线完整实现（ADR-005）
- [x] Action 定义 DSL：target、requires、gate、validate、execute
- [ ] Notification Action 子类型：无 target，跳过 State 和 Validate
- [x] 拒绝事件记录：被拒操作也写入 Event Log

### 2.3 Role + Capability 模型

- [x] Role 定义：名称 + 作用域（Ontology / 类型 / 实例）+ 授予的 Capability 列表
- [x] Capability 派生与校验：从 Role 解析当前有效的 Caller Capability
- [ ] Capability 缓存与失效：Role 变更时主动淘汰缓存
- [ ] 拒绝事件分析工具：统计 Agent/用户的越权尝试频率
- [ ] 知识库权限：`KnowledgeCapability`（KNOWLEDGE_QUERY / CREATE / MANAGE / ADMIN）
- [ ] Role DSL 中 `knowledge_restrictions` 配置（allowed_types、denied_types、max_depth）
- [x] 知识搜索/SDK 查询时 SQL 层透明权限过滤（domain + type + status — `KnowledgePerspectiveFilter`）

### 2.4 State Machine

- [x] 状态机定义：在 Resource Type 上声明状态节点 + 合法迁移边（ADR-007 Rule 2）
- [x] 迁移校验：Action 执行时检查 target 当前状态是否允许该迁移
- [x] 状态迁移事件：每次迁移产生独立的事件类型（非普通 Property 变更）

### 2.5 Relationship 与 Graph Store

- [x] 三种 Relationship 语义实现：Ownership / Reference / Association（ADR-006 — `RelationshipInference` rule）
- [ ] Graph Store：关系存储（PostgreSQL 邻接表或 Neo4j 集成）
- [ ] 级联删除行为：基于关系语义自动决定
- [ ] 权限沿 Ownership 链传播

### 2.6 Governance（基础版）

- [x] Proposal 流程：Schema 变更需提案 → 评审 → 审批（ADR-002）
- [x] Schema 变更审计日志
- [x] 管道 C（知识反哺）：`GlossaryExtractor` + `MetricExtractor`
- [x] 知识反哺 Proposal 生成 → 人类审批流程
- [x] API：`POST /v1/knowledge/promote`
- [x] review → published 审批集成（Proposal 系统）
- [ ] 自动归档建议（stale published articles）
- [ ] 知识覆盖物化视图

**退出标准**：所有写入经 Action 层执行，Abilities 约束在类型层生效，Role 控制人类用户的 Capability。Event Log 记录全部写入和拒绝事件。

---

## Phase 3: AI Agent 集成（AI Agent Integration）

**目标**：实现成熟度模型阶段三——AI Agent 成为本体的正式消费者，通过专用 Role 获得有限操作能力。

**预计周期**：8-10 周

### 3.1 AI Agent SDK

- [x] Agent SDK（Python）：封装 Query + Function + Action 调用（`heirloom-sdk/heirloom_sdk/__init__.py` — 91 行基础实现）
- [x] Agent 身份注入：SDK 携带 Agent 唯一标识，通过 Auth 步骤（`X-Agent-Id` / `X-Agent-Role` headers）
- [ ] Agent 专用 Role 定义模板：最小能力集（query + notification 仅）
- [ ] 知识库 SDK 方法：`knowledge.search()`、`knowledge.getContext()`、`knowledge.traverse()`、`knowledge.getPrerequisites()`
- [ ] 自动上下文注入：`agent.actions.execute()` 自动调用 `getPrerequisites` 并注入 LLM 上下文
- [ ] 知识审计事件：KNOWLEDGE_SEARCH、KNOWLEDGE_CONTEXT_FETCH、KNOWLEDGE_ACCESS_DENIED
- [x] 设计参考：[ADR-035 Agent SDK & Perspective 集成](../docs/adr/035-agent-sdk-perspective.md)

### 3.2 Function 引擎

- [x] Function 定义与注册：代码模板 + 参数 Schema + 返回类型（ADR-008 — `Function` entity + `FunctionRepository`）
- [x] Function 执行沙箱：受限的运行时环境（资源限制、超时、网络白名单） — `SpelFunctionExecutor` 使用 `SimpleEvaluationContext.forReadOnlyDataBinding()` 禁止方法调用 / 构造器 / Bean 查找；`Future.get(timeout)` 强制超时（默认 5s，可配置）；网络由 SpEL 无 I/O API 实现"零"访问能力。物理 / OS 级网络白名单和内存硬限制待 Phase 4
- [x] Function 在 Action validate 内嵌调用：如 `risk_score()` 用于 `update_tier` 验证 — `FunctionService.invoke()` 可被 Action 流水线调用，Action.validationRules JSONB 约定形如 `{"function": "risk_score", "inputs": {...}}`
- [x] Function 审计：可配置的审计开关（高风险 Function 开启，高频 UI Function 关闭） — `Function.auditEnabled` 字段 + `FUNCTION_INVOKED` 事件类型

### 3.3 语义搜索

- [x] 向量索引：为 Resource 的文本字段生成 embedding 并索引（pgvector）
- [x] 知识库向量搜索：`knowledge_articles.embedding` 启用，写入时自动生成 embedding（`EmbeddingBatchService` + `OpenAiEmbeddingProvider`）
- [x] 混合搜索：向量相似度 + 关键词过滤（JSON DSL 中的 `search` 块）— `RrfScorer` 实现 RRF 融合
- [ ] 知识库混合搜索：全文 + 向量（`/v1/knowledge/search?q=...&mode=hybrid`）
- [ ] Agent 自然语言查询 → JSON DSL 翻译（LLM 辅助生成查询）
- [ ] Agent SDK 知识查询方法：`heirloom.knowledge.search(...)`
- [ ] Agent 自动知识生成：操作后总结经验 → draft KnowledgeArticle

### 3.4 Agent 审计与监控

- [x] Agent 行为看板：查询频率、Action 调用、被拒操作趋势 — `/v1/audit/actors/{actor}/activity` 返回按事件类型分组的计数
- [x] Agent Role 异常检测：高频被拒 → 告警 — `/v1/audit/actors/{actor}/anomaly` 基于拒绝率阈值（默认 25%）判定；高于阈值且样本量足够时 `flagged=true`
- [x] Agent 操作回放：通过 Event Log 重建 Agent 在某任务中的完整决策链 — `/v1/audit/actors/{actor}/replay` 返回时序事件列表

**退出标准**：Agent 可以在专用 Role 下查询、调用 Function 和执行有限 Action。被拒操作自动记录并进入审计看板。

---

## Phase 4: 治理与规模化（Governance & Scale）

**目标**：实现成熟度模型阶段四——完整的 Ontology 治理、多 Agent 协作和条件式安全控制。

**预计周期**：12-16 周

### 4.1 Ontology 治理增强

- [ ] Ontology 分支（Branching）：在独立分支上修改 Schema，测试后合并（类似 Git）
- [ ] 合并冲突检测与解决
- [ ] 跨 Ontology 的 RID 映射与联邦查询：多部门 Ontology 的互操作基础
- [ ] 知识条目版本化（每次更新保存旧版本 snapshot）
- [ ] 知识条目审批流程（draft → review → published）
- [ ] 知识图谱可视化：实体 + 知识条目的引用关系图

### 4.2 条件式 Abilities

- [ ] 状态条件：Abilities 随 Resource 状态动态变化（如「仅 Draft 状态的订单可 drop」）
- [ ] 时间条件：Abilities 的时效性限制（如「仅工作时间内可 mutate」）
- [ ] 上下文条件：基于调用上下文的动态 Capability 派生（如「仅通过 Workshop UI 发起的操作，不可通过 API」）

### 4.3 存储层可扩展性

- [ ] Graph Store 独立部署：支持专用图数据库（Neo4j）作为 Graph Store 后端
- [ ] Event Log 独立部署：Kafka 作为事件总线 + 长期存储
- [ ] 读写分离与水平扩展

### 4.4 开发者体验

- [ ] Heirloom CLI：`heirloom type create`、`heirloom action test` 等
- [ ] 本地开发环境：单机版 Heirloom（docker-compose），用于离线建模和测试
- [ ] Schema 可视化：Web UI 展示 Resource Type 关系图、状态机和 Abilities 矩阵 — **Workshop（合并到 main）已实现 Schema/Query/Security 三个 tab，部分覆盖此目标**

**退出标准**：支持 Branching 治理、条件式 Abilities、多存储后端可替换。

---

## Phase 5: 可信自治与生态（Trusted Autonomy & Ecosystem）

**目标**：Agent 在严格约束下实现自主操作。Heirloom 成为企业 AI Agent 的标准语义界面。

**周期**：持续演进

### 5.1 高级 Agent 自主操作

- [ ] 渐进式信任模型：基于 Agent 历史行为的 Role 自动扩缩
- [ ] Agent 协作：多个 Agent 以不同 Role 协作完成复杂任务
- [ ] 人机协作工作流：Agent 在不确定时主动请求人类批准

### 5.2 正式方法（探索性）

- [ ] 静态分析：证明特定 Role 的 Agent 无法到达特定 Resource 的特定状态
- [ ] 模型检查：验证状态机定义是否存在死锁或不可达状态
- [ ] Abilities 完备性分析：判定类型定义是否遗漏了业务必需的能力

### 5.3 生态建设

- [ ] Connector 市场：社区贡献的数据源连接器
- [ ] Function 库：预置的通用业务计算（信用评分、合规检查、异常检测）
- [ ] Agent 模板：面向特定垂直领域（供应链、金融、医疗）的预配置 Role 和 Action 集

### 5.4 开放问题跟踪

以下问题由 Part 6（总结与愿景）和 Part 5（局限与非目标）标记——本阶段不承诺解决，但持续跟踪：

- [ ] 多 Ontology 联邦查询与语义冲突解决
- [ ] 跨存储的分布式事务一致性保障
- [ ] Agent 行为的正式验证

---

## 版本历史

| 日期 | 版本 | 说明 |
|------|------|------|
| 2026-05-28 | v0.1 | 初始路线图，基于白皮书和 ADR 系列 |
| 2026-06-22 | v0.2 | 批量同步代码现状：Phase 0–3 大部分核心子项已落地（95 commits ahead of origin/main）；Phase 0.2 Resource Store 正式 descope（设计重心转向外部源语义映射，ADR-003/018）；Phase 1.3 通用 Perspective Engine 留待 Phase 2.3 KnowledgeCapability 合并时实现；Workshop 已合并进 main，覆盖部分 Phase 4.4 目标 |
| 2026-06-22 | v0.3 | Phase 3.2 Function 引擎落地（SpEL 沙箱执行器 + /v1/functions/{name}/invoke + audit 开关）；Phase 3.4 Agent 审计看板落地（activity / anomaly / replay / entity-history 4 个端点）；heirloom-sdk 升到 0.4.0（覆盖 knowledge / proposals / functions / audit 4 个 namespace） |