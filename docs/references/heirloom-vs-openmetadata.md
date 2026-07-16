# Heirloom vs OpenMetadata — 元数据层架构对比

**日期**: 2026-07-16
**参考源码**: `_references/OpenMetadata-main/`
**对比范围**: Heirloom「第一层」（元数据目录 / 数据建设）vs OpenMetadata 元数据平台

---

## 1. 概述

OpenMetadata（OM）是一个企业级统一元数据平台，覆盖数据发现、血缘、质量、治理、协作五大领域。Heirloom 的「第一层」目标是对标 OM 的元数据目录能力，但当前实现（4 个实体）与 OM（80+ 个 JSON Schema 定义的实体）之间存在数量级的差距。

> 注：本对比聚焦于元数据层。Heirloom 独有的「第二层」——语义操作层（ResourceType、Abilities、StateMachine、Action Pipeline、Agent SDK）在 OM 中无对应物，不做对比。

---

## 2. 实体模型对比

### 2.1 实体数量与分类

| 分类 | OpenMetadata | Heirloom 当前 | Heirloom 规划中 |
|------|-------------|--------------|----------------|
| **数据资产** | 33 实体（Table, Dashboard, Pipeline, Topic, ML Model, Container, SearchIndex, API, ...） | 1（`TableEntity`） | ~12 |
| **服务连接** | 14 实体（DatabaseService, DashboardService, PipelineService, ...） | 0（硬编码在 `DiscoverySource.connectionConfig` JSONB 中） | 待定 |
| **血缘** | 1 + Edge 模型 + 列级血缘 | 1（`LineageEntity`，仅表级 FK 推断） | 同 |
| **数据质量** | 3 核心实体（TestDefinition, TestCase, TestSuite）+ 时间序结果 | 1（`TableProfileEntity`，仅 rowCount/sizeInBytes/freshness） | ~5 |
| **Profiling** | 列级指标时间序（entityProfile 实体） | 0（`columnProfiles` JSONB 字段，空壳） | ~2 |
| **术语表** | 2（Glossary + GlossaryTerm，含层次、同义词、外部概念映射） | 0 | ~2 |
| **分类/标签** | 2（Classification + Tag，含层次标签、样式） | 0 | ~2 |
| **领域/数据产品** | 2（Domain + DataProduct） | 1（`DomainEntity`，仅 name+description+owner） | 待定 |
| **团队/用户/角色** | 5（Team, User, Role, Policy, Persona） | 1（`Role`，语义层用） | 待定 |
| **访问控制** | 3（Policy + Rule + ResourceDescriptor） | 0 | 待定 |
| **治理工作流** | Flowable BPMN 引擎 + 5 实体 | 0 | 0（Proposal 系统覆盖 Schema 变更） |
| **数据契约** | 7 实体（DataContract + 4 种 Validation） | 0 | 0 |
| **AI 治理** | 10 实体（AIApplication, LLMModel, PromptTemplate, MCP, ...） | 0 | 0 |
| **活动 Feed** | 12 实体（Thread, Announcement, Suggestion, ...） | 0 | 0 |
| **事件/Webhook** | 3 实体（EventSubscription, NotificationTemplate, Webhook） | 0 | 0 |
| **自动化工作流** | 5 实体（Workflow, WorkflowDefinition, ...） | 0 | 0 |
| **应用平台** | 6 实体（App, AppMarketPlaceDefinition, ...） | 0 | 0 |
| **合计** | **~80+** | **4** | **~25** |

### 2.2 实体设计深度对比

以 `Table` 为例：

| 维度 | OpenMetadata `Table` | Heirloom `TableEntity` |
|------|---------------------|----------------------|
| **Schema 定义** | JSON Schema（`table.json`），~400 行 | JPA Entity（`TableEntity.java`），61 行 |
| **字段数量** | 50+ 字段（含嵌套对象） | 15 字段 |
| **列模型** | 独立 `Column` 实体，含 type/precision/dataTypeDisplay/tags/constraints/children | 内嵌 `columnsJson` JSONB 字符串 |
| **约束** | 含 `constraints`（PRIMARY_KEY, FOREIGN_KEY, UNIQUE, ...） | 不存储 |
| **标签** | 支持 `tags`（Classification→Tag 层次标签） | 不支持 |
| **所有者** | `owners`（多所有者，含类型如 DataSteward/DataOwner） | `owner`（单字符串） |
| **领域** | `domain`（领域关联 + 继承） | `DomainEntity` 独立存在，Table 无法直接关联 |
| **数据产品** | `dataProducts`（多数据产品关联） | 不支持 |
| **数据契约** | `dataContract`（关联 DataContract 实体） | 不支持 |
| **生命周期** | `lifeCycle`（Created → InProgress → Deprecated → Deleted） | `deleted` 布尔软删 |
| **认证** | `certification`（Bronze/Silver/Gold + 过期时间） | 不支持 |
| **使用统计** | `usageSummary`（30 天查询频率） | 不支持 |
| **版本化** | 每次更新产生新版本号 + `changeDescription` 字段级 diff | `@Version` 乐观锁，无变更描述 |
| **血缘** | 通过 `entity_relationship` 表关联 | `LineageEntity` 独立存储 FQN 字符串 |
| **扩展性** | `extension`（任意 JSON 扩展字段） | `columnsJson` 可承载但不规范 |
| **源码 Hash** | `sourceHash`（用于增量同步判断） | `changeHash`（类似但不用于增量同步） |
| **Profile 数据** | 独立时间序 `entityProfile` 实体 | 独立 `TableProfileEntity`（仅 3 个指标） |

### 2.3 Heirloom 已采用的 OM 模式

以下模式是 Heirloom 从 OM 成功借鉴的（ADR-010 确认）：

| OM 模式 | Heirloom 对应 | 状态 |
|---------|--------------|------|
| `EntityInterface` | `HeirloomEntity` | 已实现 |
| `EntityRepository<T>` | `EntityRepository<E>` | 已实现 |
| `EntityResource<T,K>` | `EntityResource<E>` | 已实现 |
| `Entity.java` 中央注册 | `EntityRegistry` | 已实现 |
| `ChangeEventHandler` | `ChangeEventInterceptor` | 已实现 |
| `Authorizer` 接口 | `Authorizer`（Noop/RoleBased） | 已实现 |
| FQN 命名体系 | `{domain}.{type}.{name}` 模式 | 已实现 |
| 分散式注册 | 各 Repository 在 `@PostConstruct` 自注册 | 已实现 |

以下模式 Heirloom 明确不采用（ADR-010）：

| OM 模式 | 理由 |
|---------|------|
| JDBI3 DAO | Heirloom 用 Spring Data JPA |
| Elasticsearch 搜索 | Phase 0 不需要，用 pgvector 替代 |
| JSON Schema → 代码生成 | Phase 1 评估（Java-first 先） |

---

## 3. 数据摄入（Ingestion）对比

### 3.1 连接器生态

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **连接器总数** | 95+ | 2 |
| **数据库** | 48（PostgreSQL, MySQL, BigQuery, Snowflake, Redshift, ...） | 2（PostgreSQL, MySQL） |
| **BI/仪表盘** | 17（Tableau, Looker, PowerBI, Superset, ...） | 0 |
| **数据管道** | 13（Airflow, dbt, Dagster, Fivetran, ...） | 0 |
| **消息系统** | 4（Kafka, Kinesis, PubSub, Redpanda） | 0 |
| **对象存储** | 2（S3, GCS） | 0 |
| **ML 模型** | 2（MLflow, SageMaker） | 0 |
| **搜索引擎** | 2（Elasticsearch, OpenSearch） | 0 |
| **API** | 1（REST） | 0 |
| **云盘** | 2（Google Drive, SFTP） | 0 |
| **安全** | 1（Apache Ranger） | 0 |
| **元数据导入** | 3（Alation, Amundsen, Atlas） | 0 |
| **MCP** | 1 | 0 |
| **摄入框架语言** | Python 3.10+ | Java |
| **连接器接口** | `Source` + `SourceStatus`（Pydantic） | `SchemaExtractor`（Java interface） |

### 3.2 摄入管线能力

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **Schema 提取** | 全量 + 增量（sourceHash diff） | 全量（`RawSchema` → `InferencePipeline`） |
| **数据 Profiling** | 40+ 指标，多线程，采样，分引擎实现 | 不支持（`TableProfileEntity` 为未来预留空壳） |
| **数据采样** | 可配置采样比例 + 采样类型 | 不支持 |
| **数据质量测试** | 200+ 测试定义，SQA/Pandas 双接口 | 不支持 |
| **PII 检测** | 内置 PII 识别引擎 | 不支持 |
| **自动分类** | `AutoClassificationWorkflow` 继承 Profiler | 不支持 |
| **增量同步** | 基于 `sourceHash` / `changeDescription` | 不支持（每次全量） |
| **调度** | Airflow 集成 + 内置 workflow | 不支持（仅手动 API 触发） |
| **多线程** | 可配置并行度 | 单线程 |
| **错误处理** | `SourceStatus` 分 success/failure/warning | 异常直接抛出 |
| **血缘提取** | 查询日志解析 + dbt/OpenLineage 集成 | 仅 FK 推断 |

### 3.3 Heirloom Discovery 的定位差异

Heirloom 的 `DiscoveryService` 不仅是 Schema 提取，还包含 `InferencePipeline`（6 条规则推断 ResourceType）。这是 OM 不具备的「语义推断」能力。OM 的摄入产出是元数据实体（Table, Column, Lineage），Heirloom 的摄入额外产出语义提案（ResourceType Proposal）。

---

## 4. 数据质量 & Profiling 对比

### 4.1 质量框架

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **质量模型** | TestDefinition → TestCase → TestSuite 三层模型 | 不存在 |
| **测试定义** | 200+ 通用定义（columnNullCount, columnValueMax, tableRowCount, ...） | 不存在 |
| **测试套件** | ExecutableTestSuite（绑定实体）+ LogicalTestSuite（跨实体） | 不存在 |
| **执行引擎** | SQA（SQLAlchemy）+ Pandas + NoSQL 三引擎 | 不存在 |
| **结果存储** | 时间序实体（testCaseResult, testCaseDimensionResult） | 不存在 |
| **事件管理** | testCaseResolutionStatus（NEW → ACK → ASSIGNED → RESOLVED） | 不存在 |
| **数据契约** | DataContract + 4 种 Validation（Schema/Quality/Semantics/SLA） | 不存在 |
| **Great Expectations 集成** | 支持 | 不支持 |

### 4.2 Profiling

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **指标数量** | 40+ 列级指标（nullCount, distinctCount, min/max/mean/stddev, histogram, quartiles, top values, ...） | 3（rowCount, sizeInBytes, freshness） |
| **执行引擎** | 分数据库实现的 ProfilerInterface（Snowflake, BigQuery, Redshift, Trino, ...） | 不存在 |
| **采样** | 可配置采样比例、采样类型、表级覆盖 | 不存在 |
| **多线程** | 可配置线程数 | 不存在 |
| **结果存储** | 独立时间序实体 entityProfile | `TableProfileEntity`（一次只存一个快照） |
| **自动分类** | 基于 Profiling 结果自动推荐分类标签 | 不存在 |
| **数据量感知** | 大表自动降级为采样模式 | 不支持 |

### 4.3 Heirloom ADR-041 的规划

ADR-041（Proposed）定义了 `ProfilingService`、`AlignmentService`、`EntityResolutionService` 三个接口，但 ADR-045 将其推迟到 MVP 之后。这意味着 Heirloom 有清晰的数据质量路线图，但尚未开工建设。

---

## 5. 血缘对比

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **血缘模型** | 有向图：nodes + upstreamEdges + downstreamEdges | `LineageEntity`（fromEntityFQN → toEntityFQN） |
| **表级血缘** | 是 | 是 |
| **列级血缘** | 是（columnsLineage + transformation function） | 否（`columnMappings` JSONB 预留但未实现） |
| **血缘来源** | 10 种（Manual, ViewLineage, QueryLineage, PipelineLineage, DashboardLineage, DbtLineage, SparkLineage, OpenLineage, ExternalTableLineage, CrossDatabaseLineage） | 1（fk_inference） |
| **SQL 解析** | 查询日志解析自动提取血缘 | 不支持 |
| **dbt 集成** | 原生支持 | 不支持 |
| **OpenLineage** | 原生 HTTP 端点接收 OL 事件 | 不支持 |
| **Airflow** | 通过 OpenLineage / 自定义 Operator | 不支持 |
| **血缘图存储** | Elasticsearch/OpenSearch（图遍历搜索） | PostgreSQL（简单 FQN 关联） |
| **中间表** | 支持 tempLineageTables | 不支持 |
| **血缘可视化** | UI 原生支持（节点图） | Workshop 有 OntologyGraph 但非血缘图 |
| **跨源血缘** | 支持（CrossDatabaseLineage） | 不支持 |
| **血缘广度/深度配置** | 可配置 upstream/downstream depth | 不支持 |

---

## 6. 治理对比

### 6.1 术语表

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **术语表** | Glossary → GlossaryTerm（层次结构） | 不存在 |
| **同义词** | 支持 | 不支持 |
| **关联术语** | 支持 | 不支持 |
| **外部引用** | termReference（指向外部源） | 不支持 |
| **概念映射** | SKOS 风格（EXACT_MATCH, CLOSE_MATCH, BROAD_MATCH, RELATED_MATCH） | 不支持 |
| **审批流程** | 内置 Reviewer 审批 | 不支持 |
| **实体关联** | 术语可标记到 Table/Column/Dashboard 等任何实体 | 不支持 |
| **术语表版本化** | 是 | 不支持 |

### 6.2 分类与标签

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **分类体系** | Classification → Tag（两层层次） | 不存在 |
| **标签样式** | 标签有颜色/图标（UI 用） | 不存在 |
| **字段级标签** | 列级标签（PII、敏感等级等） | 不存在 |
| **自动分类** | 基于 Profiling 结果自动推荐 | 不存在 |
| **标签传播** | 父实体标签可传播到子实体 | 不支持 |

### 6.3 领域与数据产品

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **领域** | 层次化领域 | `DomainEntity`（平铺，仅 name+description+owner） |
| **数据产品** | DataProduct（领域下的逻辑分组） | 不存在 |
| **领域继承** | 实体可从父领域继承所有者/标签 | 不支持 |

### 6.4 工作流

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **工作流引擎** | Flowable BPMN（流程定义 + 用户任务 + 自动任务 + 触发器） | 不存在 |
| **审批流程** | 术语审批、测试事件处理、数据产品审批 | Proposal 系统（仅 Schema 变更） |
| **知识审批** | 无 | KnowledgeWorkflowService（draft→review→published） |

---

## 7. 搜索对比

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **搜索引擎** | Elasticsearch/OpenSearch 双后端（22+23 个实现文件） | PostgreSQL（LIKE 子串匹配） |
| **每实体索引** | 78 个 SearchIndex 实现（每实体一个） | 不存在 |
| **全文搜索** | 是（多字段权重） | 知识库：tsvector GIN 索引；类型：子串匹配 |
| **聚合** | ES/OS 原生聚合 | 不支持 |
| **建议/自动补全** | 是 | 不支持 |
| **向量搜索** | vector search（AI/RAG） | pgvector（知识库混合搜索） |
| **血缘图搜索** | ES/OS 中构建图 → 广搜/深搜 | 不支持 |
| **索引映射验证** | 启动时自动验证索引 mapping | 不支持 |
| **排序** | 多字段可配置排序 | 基础排序 |

---

## 8. 架构与技术栈对比

| 维度 | OpenMetadata | Heirloom |
|------|-------------|----------|
| **后端语言** | Java 21 | Java 21 |
| **后端框架** | Dropwizard + JAX-RS | Spring Boot 3.5 |
| **数据访问** | JDBI3（手写 SQL） | Spring Data JPA + Hibernate |
| **Schema 定义** | JSON Schema（openmetadata-spec）→ 代码生成（Java POJO, TS types, Python Pydantic） | Java Entity 类（无 Schema-first 代码生成） |
| **摄入框架** | Python（Pydantic, SQLAlchemy, Pandas） | Java（硬编码 JDBC） |
| **搜索引擎** | Elasticsearch + OpenSearch 双实现 | PostgreSQL（pgvector） |
| **工作流引擎** | Flowable BPMN | 不存在 |
| **前端** | React + Ant Design + MUI | React + Mantine（Workshop） |
| **模块化** | 多 Maven 模块 + 多语言代码生成 | 单 Maven 模块（ADR-036 计划拆分为 heirloom-core + connector + server） |
| **测试** | 分层测试 + 集成测试套件 | JUnit + Testcontainers（192 测试通过） |
| **部署** | Docker Compose + K8s Operator + Airflow | 单 JAR + PostgreSQL |
| **API 规范** | OpenAPI / Swagger | SpringDoc（`/docs`） |
| **GraphQL** | 无 | 有（HeirloomGraphQLController） |

---

## 9. Heirloom 不应照搬的 OM 设计

以下 OM 架构决策不适合 Heirloom 的定位：

| OM 设计 | 不适合 Heirloom 的原因 |
|---------|----------------------|
| **Dropwizard + JDBI3** | Heirloom 已基于 Spring Boot + JPA，ADR-010/021 已明确技术栈选择 |
| **Elasticsearch 作为搜索引擎** | 引入重量级基础设施，Heirloom 已有 pgvector，Phase 0/1 不需要 |
| **Flowable BPMN 工作流引擎** | 白皮书明确「不提供业务流程引擎（BPMN）」，Heirloom 不是 Camunda 替代品 |
| **80+ 实体全覆盖** | Heirloom 不是要做 OpenMetadata 的 Java 版竞品——元数据层服务于语义操作层，不需要 Dashboard/Pipeline/AI Model 等非核心实体 |
| **多个消费者入口（Feed, Tasks, Announcements）** | Heirloom 的消费者是 AI Agent 和 Workshop 操作者，不是企业内部社交协作 |
| **Python 摄入框架** | Heirloom 需要 Java 优先（与后端同语言），但分离为独立 Connector 模块（ADR-036） |
| **JSON Schema → 代码生成** | Phase 1 可选，Phase 0 过度设计——Java Entity 类 + JPA 已足够 |

---

## 10. Heirloom 应重点补课的 OM 能力

按优先级排序的「第一层」建设路线：

### P0 — 基础元数据实体完善

| 当前状态 | 目标 | OM 对应 |
|---------|------|---------|
| `TableEntity` 无 Column 独立建模 | Column 独立 Entity（或结构化 JSONB 索引） | OM `Column` schema |
| `TableEntity` 无约束存储 | 主键/外键/唯一约束存储 | OM `tableConstraints` |
| `TableEntity` 无标签体系 | Classification → Tag 两层体系 | OM `classification` + `tag` |
| `DomainEntity` 极简 | Domain 层次化 + 实体可关联到 Domain | OM `domain` |
| 无 DataProduct | DataProduct 逻辑分组 | OM `dataProduct` |

### P1 — Profiling & 数据质量

| 当前状态 | 目标 | OM 对应 |
|---------|------|---------|
| `TableProfileEntity` 仅 3 个字段 | 列级 20+ 指标（nullRate, distinctCount, min/max/mean, topValues, ...） | OM `ColumnProfile` |
| 无质量引擎 | TestDefinition → TestCase → TestSuite 三层模型 | OM dataQuality 模块 |
| 无自动分类 | 基于 Profiling 的 DataClass 推断（EMAIL, PHONE, ENUM, ...） | OM `AutoClassificationWorkflow` |

### P2 — 血缘增强

| 当前状态 | 目标 | OM 对应 |
|---------|------|---------|
| 仅表级 FK 血缘 | 列级血缘 + 多来源（View, QueryLog, dbt） | OM `columnsLineage` |
| 仅一种血缘来源 | 多来源血缘（至少 dbt + 手动标注） | OM 10 种 lineageSource |
| 无血缘图查询 | 上下游行图遍历 API | OM LineageRepository |

### P3 — 搜索增强

| 当前状态 | 目标 | OM 对应 |
|---------|------|---------|
| 子串匹配 | 全文搜索 + 聚合搜索 | OM 78 个 SearchIndex |
| 无 Quick Search | 跨实体搜索 API | OM SearchRepository |

---

## 11. Heirloom 的差异化优势

在补课的同时，Heirloom 不应丢失自身优势：

| Heirloom 独有能力 | OM 无法替代的原因 |
|------------------|-----------------|
| **ResourceType + Abilities 类型安全** | OM 是「描述数据」，Heirloom 是「约束操作」——两者不互相替代 |
| **StateMachine 状态机** | OM 只有 LifeCycle 枚举，无带 guard 的迁移图 |
| **9 步 Action 管线** | OM 没有写入安全管线，写入是 CRUD 直通 |
| **AI Agent 一等消费者** | OM 的 Agent 集成是后来的（MCP），非原生设计 |
| **语义查询 DSL** | OM 只能用 SQL 查 Table/Column，无 Resource 语义查询 |
| **Proposal + Branch 治理** | OM 有审批但无 Git 风格分支合并 |
| **统一知识库** | OM 无文档/知识管理（仅有 Article 实体但无管线） |
| **MCP 原生** | Heirloom 从设计初期就内置 MCP 支持 |

---

## 12. 总结

OpenMetadata 经过 5 年+ 的发展，在元数据目录领域达到了企业级完备度。Heirloom 的「第一层」当前只完成了骨架搭建（`EntityRegistry`、`HeirloomEntity`、`EntityRepository`、`EntityResource`），而实体内容（Table/Column 模型、数据质量、血缘、术语表）几乎空白。

**核心结论：**

1. **补数量**：从 4 个元数据实体扩展到 20-25 个，重点在 Column（独立建模）、Tag/Classification、DataProduct、TableProfile（丰富化）

2. **补深度**：单个实体（如 Table）的字段从 15 个扩展到对标 OM 的 50+，重点在 constraints、tags、owners、domain、certification、lifeCycle

3. **补管道**：Profiling 管线 + 数据质量框架是最大的功能空白，需要按 ADR-041 的契约逐步实现

4. **不照搬**：ES 搜索、Flowable 工作流、社交 Feed、95+ 连接器不是 Heirloom 的目标——Heirloom 的元数据层是为语义操作层服务的，不是要做另一个 DataHub

5. **渐进式**：按 ADR-045 的三阶段策略（MVP → 增强 → 规模化），不要试图一次性对标 OM 的全部能力
