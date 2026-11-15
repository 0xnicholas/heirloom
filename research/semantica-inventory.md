# Semantica 能力、数据模型与接口全景

> 研究档案，供 heirloom 架构蓝图直接引用。
> 研究对象：`semantica-agi/semantica`，本地检出 commit `3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b`（2026-11-15），包版本 `0.8.0`。
> 一手来源：官方文档站 <https://docs.getsemantica.ai/>、仓库 `README.md` / `ARCHITECTURE.md` / `docs/` / `CHANGELOG.md`、源码 `semantica/` / `semantica_mcp/` / `explorer/`、测试 `tests/`。
> 引用约定：`path:line` 均相对仓库根目录；对应 GitHub 链接前缀为 `https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/`。官方文档链接直接给出站点 URL。

---

## 0. 结论摘要

以下 12 条是会影响 heirloom 架构决策的事实，详细证据见后文。

1. **“四层架构”是文档分类，不是可执行的依赖边界。** 官方文档把 `ingest/split`、`parse/normalize/semantic_extract/dedup/conflicts`、`kg/vector_store/graph_store/triplet_store/embeddings/ontology`、`context/reasoning/export/visualization/explorer/pipeline` 分为四层，并声称“clean interfaces / no hidden coupling”；实际存在 `context ↔ kg`、`context ↔ graph_store`、`split ↔ semantic_extract`、`ontology ↔ change_management` 等循环依赖。`split` 在模块图里属第 1 层、在数据流里属处理阶段；`kg.GraphBuilder` 被放在第 2 层“Build”，但 `kg` 整体属第 3 层。【[docs/architecture.md:3-98](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/docs/architecture.md#L3-L98)】【源码依赖见 §2.3】

2. **`ContextGraph` 是一个线程安全的内存属性图，不是 RDF 存储，也不是持久化数据库。** 它由 `dict` 节点、`list` 边、邻接表、类型索引和 `RLock` 组成，默认不落盘；`save_to_file()` 写整图 JSON 或 Markdown 目录。决策、实体、类别、决策者都是同一种 `ContextNode`，靠 `node_type` 字符串区分。【[context_graph.py:564-672](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L564-L672)】【[context_graph.py:1383-1544](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L1383-L1544)】

3. **Semantica 没有一等公民的 `Fact` 或 `Ontology` 对象，Provenance 则是独立存储的旁路条目。** 事实（fact）主要是关系字典/边，`BiTemporalFact` 只是关系字典的包装器；本体是 `{uri,name,version,classes,properties,metadata}` 字典；溯源条目独立存放在 `ProvenanceManager` 的 SQLite/内存存储中，和图的写入不是同一事务，也不是自动接线。【[temporal_model.py:28-65](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/kg/temporal_model.py#L28-L65)】【[ontology_generator.py:124-155](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/ontology/ontology_generator.py#L124-L155)】【[schemas.py:37-190](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/provenance/schemas.py#L37-L190)】

4. **决策智能不是独立子系统，而是 `ContextGraph` 上的方法集合。** `record_decision()` 创建 1 个 `Decision` 节点，并为每个实体、类别、决策者创建节点和 `involves` / `belongs_to` / `made_by` 边；决策同时缓存在 `_decisions` 字典和三个索引里。另有 `add_decision(Decision)` 与 `DecisionRecorder` 两条旁路，字段和时间格式并不完全一致。【[context_graph.py:4450-4617](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L4450-L4617)】【[context_graph.py:5123-5255](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L5123-L5255)】【[decision_recorder.py:206-250](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/decision_recorder.py#L206-L250)】

5. **Precedent search 不是语义检索，而是词面 Jaccard + 图度数先验。** `find_precedents_by_scenario()` 接受 `use_semantic_search=True` 但函数体从不使用它；实际分数是 `0.7 * 词面相似度 + 0.3 * 结构相似度`。官方决策指南自己承认“match is lexical rather than embedding-based”，而 README 和 MCP 文档仍写成 “semantic precedent search” / “hybrid similarity”。【[context_graph.py:4618-4740](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L4618-L4740)】【[decision-intelligence.md:159](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/docs/guides/decision-intelligence.md#L159)】【[README.md:208](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/README.md#L208)】

6. **“因果链 / 影响分析”混合了显式因果边与启发式相关。** `add_causal_relationship()` 只允许 `CAUSED / INFLUENCED / PRECEDENT_FOR`，但 `trace_decision_causality()` 还会把“共享实体且时间更早”的决策当作 `influences` 原因；`analyze_decision_influence()` 把同类别、共享任意实体、以及显式因果边都算作直接“影响”。这不是因果识别，不能当作审计意义上的因果关系。【[context_graph.py:3934-4006](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L3934-L4006)】【[context_graph.py:4741-4835](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L4741-L4835)】【[context_graph.py:4889-5067](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L4889-L5067)】

7. **“每个事实都有 W3C PROV-O 溯源”在默认路径上不成立。** `ContextGraph` 源码中 `provenance` 出现次数为 0；`ContextNode`/`ContextEdge` 没有文档来源字段（`ContextEdge.source_id` 只是边起点），也没有 `activity`、`agent` 或 `derived_from` 字段。配置了 `provenance_manager` 时，`DecisionRecorder` 的溯源路径还把缺失的 `source` 参数漏掉，并调用 `ProvenanceManager` 中不存在的 `track_activity()`，异常被吞掉；测试用 mock 补上了这个方法，因此没有暴露。【[context_graph.py:420-530](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L420-L530)】【[decision_recorder.py:709-737](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/decision_recorder.py#L709-L737)】【[manager.py:291-300](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/provenance/manager.py#L291-L300)】【[test_decision_recorder.py:36-40](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/tests/context/test_decision_recorder.py#L36-L40)】

8. **双时态只在 KG 查询层成立，`ContextGraph` 只有有效时间。** `TemporalGraphQuery` 有 `valid` / `transaction` / `both` 三个时间轴；`ContextGraph` 的 `state_at()` 只用 `valid_from/valid_until`。官方 `to_kg_dict()` 把边的 `recorded_at` 放进嵌套 `metadata`，而 `TemporalGraphQuery` 读取顶层 `recorded_at`，因此事务时间在官方适配器上对查询不可见。`BiTemporalFact` 在缺少 `recorded_at` 时还默认用 `valid_from` 冒充记录时间。【[temporal_query.py:752-761](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/kg/temporal_query.py#L752-L761)】【[context_graph.py:3760-3803](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L3760-L3803)】【[temporal_model.py:40-60](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/kg/temporal_model.py#L40-L60)】

9. **Allen 区间代数有开放区间缺陷，时间边界也不一致。** `relation()` 把 `TemporalBound.OPEN` 当成 `datetime.max`；两个不同起点、都开放结束的区间会被判为 `finished_by`，而不是 `contains/during`。`TemporalReasoningEngine.active_at()` 用半开区间 `point < end`，`ContextNode.is_active()` 用闭区间 `now > end`，同一个 `valid_until` 在两层语义相反。现有测试只覆盖闭合区间的 13 种关系。【[temporal_reasoning.py:48-120](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/kg/temporal_reasoning.py#L48-L120)】【[context_graph.py:431-449](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L431-L449)】【[test_temporal_reasoning.py:18-58](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/tests/kg/test_temporal_reasoning.py#L18-L58)】

10. **推理引擎能力边界清晰，但宣传强于实现。** Forward chaining 只支持 `IF ... AND ... THEN ...`，无 OR/否定/比较/算术；Rete 是“每规则独立 alpha 节点 + 正则字符串匹配”，不是共享 alpha 网络，也没有事实撤回；Datalog 支持递归但无否定/聚合/比较，且会把常量统一转小写；SPARQL 必须有 triplet store；SHACL 是真实 `pyshacl`（opt-in）；OWL DL 验证器是占位实现，没有 HermiT/Pellet。【[reasoner.py:447-495](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/reasoning/reasoner.py#L447-L495)】【[rete_engine.py:405-500](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/reasoning/rete_engine.py#L405-L500)】【[datalog_reasoner.py:70-120](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/reasoning/datalog_reasoner.py#L70-L120)】【[ontology_validator.py:326-370](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/ontology/ontology_validator.py#L326-L370)】

11. **对外表面已经漂移。** 实际 MCP 工具是 22 个（测试明确断言 22），文档写 15 个，且文档列出的 `query_graph` / `update_node` / `delete_node` 在真实工具表中不存在（真实为 `search_graph` 等）。Explorer 实际有 83 个 `@router` 路由处理器，`ARCHITECTURE.md` 写 “REST API 100+ endpoints”。MCP 的 `add_entity` 还把 `label` 和 `metadata` 当作 `**properties` 传给 `add_node`，导致节点 `content` 变成 id、`metadata` 被嵌套而不是展开。【[docs/reference/mcp_server.md:172-190](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/docs/reference/mcp_server.md#L172-L190)】【[test_mcp_tool_registry_consistency.py:44-48](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/tests/test_mcp_tool_registry_consistency.py#L44-L48)】【[ARCHITECTURE.md:67](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/ARCHITECTURE.md#L67)】【[mcp/tools/graph.py:18-27](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica_mcp/mcp/tools/graph.py#L18-L27)】

12. **性能主张不可从仓库复现。** README 的 118k 节点 / 24ms → 0.004ms / 6000× 是 v0.5.0 的历史数字；仓库的 `semantica/benchmarks` 是“memory QA harness”，明确说“no result table is published yet”；`tests/vector_store/test_performance_benchmarks.py` 测的是决策处理阈值，不测 118k 图搜索，也没有 0.004ms 断言。官方文档另给了 BFS <50ms、搜索 0.004ms 的数字，但没有数据集、硬件、脚本。【[README.md:1178-1187](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/README.md#L1178-L1187)】【[benchmarks/README.md:1-12](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/benchmarks/README.md#L1-L12)】【[test_performance_benchmarks.py:52-60](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/tests/vector_store/test_performance_benchmarks.py#L52-L60)】

---

## 1. 研究方法与证据基线

### 1.1 做了什么

- 克隆仓库：`git clone --depth 50 https://github.com/semantica-agi/semantica.git`，HEAD 为 `3ebdb95`（2026-11-15）。
- 阅读 `README.md`、`ARCHITECTURE.md`、`docs/architecture.md`、`docs/guides/*`、`docs/reference/*`、`CHANGELOG.md`、`RELEASE_NOTES.md`。
- 阅读 `semantica/` 的核心源码：`context/`、`provenance/`、`kg/`、`reasoning/`、`ontology/`、`pipeline/`、`export/`、`explorer/`、`semantica_mcp/`、`explorer/src/`。
- 阅读测试：`tests/context/`、`tests/kg/`、`tests/reasoning/`、`tests/provenance/`、`tests/test_mcp_*`、`tests/vector_store/test_performance_benchmarks.py`。
- 用 AST/正则统计模块依赖、REST 路由数、CLI 命令数、MCP 工具数。
- 对 Allen 开放区间行为做了隔离复现：把 `temporal_reasoning.py` / `temporal_model.py` 复制到临时目录，替换相对导入后运行；结果为 `open a vs open b: finished_by`，同时 `contains(a,b): True`。

### 1.2 没有做什么

- **没有完整安装并运行 Semantica 测试套件。** 环境缺少 `numpy` 等运行依赖；`import semantica` 会触发重依赖链。源码级结论来自静态阅读与隔离复现，不是端到端运行结果。
- **没有跑性能基准。** 没有 118k 节点数据集，也没有按 README 的硬件配置复现。
- **没有逐页抓取官方文档站。** 以仓库 `docs/` 为权威文本，抽查了站点 `architecture` 与 `reference/mcp_server` 页面，内容与仓库文档一致。
- 仓库是快速演进的（CHANGELOG 有 480KB，HEAD 当天仍有提交）；行号以本 commit 为准。

### 1.3 仓库规模与许可证

| 项 | 值 | 来源 |
|---|---|---|
| 包版本 | `0.8.0` | `pyproject.toml:7`、`semantica/__init__.py:13` |
| Python 要求 | `>=3.11.5,<3.14` | `pyproject.toml:31` |
| 许可证 | MIT | `LICENSE:1-3`、`pyproject.toml:10` |
| 测试文件 | 502 个 `test_*.py`，约 19 万行 | `find tests -name 'test_*.py' \| wc -l`；`find tests -name '*.py' -print0 \| xargs -0 cat \| wc -l` |
| 文档站元数据 | 站点自称 “MIT licensed” | `docs/docs.json` metadata |
| 版本漂移 | README “What's New in v0.7.0”；RELEASE_NOTES 仍为 0.5.0 | `README.md:1509`、`RELEASE_NOTES.md:6` |

> 对 heirloom 的含义：Semantica 是 MIT，heirloom 计划 Apache 2.0。参考其设计与接口没有问题；若直接复制代码，保留 MIT 版权与许可声明即可兼容 Apache 2.0。不要把 Semantica 的版本号当作稳定 API 承诺——它仍在快速变动。

---

## 2. 四层架构：官方表述 vs 实际模块图

### 2.1 官方四层

官方文档的定义是“four-layer modular architecture ... every component independently swappable ... clean interfaces with no hidden coupling”。【[docs/architecture.md:3-7](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/docs/architecture.md#L3-L7)】

| 层 | 官方模块 | 官方职责 | 官方输入/输出 |
|---|---|---|---|
| Layer 1 Ingestion | `ingest`, `split` | 从任意来源加载数据；切分 | 原始文件/网页/DB/流 → “unified `SourceDocument`”；chunk |
| Layer 2 Processing | `parse`, `normalize`, `semantic_extract`, `deduplication`, `conflicts` | 解析、规范化、抽取、去重、冲突检测 | 原始文档 → 结构化文档、实体/关系/三元组、合并结果、冲突 |
| Layer 3 Intelligence | `kg`, `vector_store`, `graph_store`, `triplet_store`, `embeddings`, `ontology` | 知识图谱、向量、图数据库、三元组存储、嵌入、本体 | 实体/关系/三元组 → 图、向量、三元组、本体 |
| Layer 4 Application | `context`, `reasoning`, `export`, `visualization`, `explorer`, `pipeline` | GraphRAG、Agent memory、决策、推理、导出、可视化、编排 | 图/向量/三元组 → 上下文、推理结果、导出物、UI/API |
| Cross-cutting | `provenance`, `change_management`, `llms`, `mcp_server`, `seed`, `evals`, `core`, `utils` | 溯源、版本、LLM、MCP、种子、评测、编排、工具 | 横切 |

来源：`docs/architecture.md:16-98`；官方站点 <https://docs.getsemantica.ai/architecture>。

**官方分层自身就有三处错位：**

1. `split` 在模块表里属 Layer 1，但在数据流里位于 Parse → Normalize 之后，是处理阶段；`split` 还依赖 `semantic_extract`（第 2 层），见 §2.3。
2. `kg.GraphBuilder` 被列在 Layer 2 的 “Build” 行，但 `kg` 整体被列为 Layer 3；同一模块跨两层。
3. `pipeline` 被列为 Layer 4，但它编排所有层；`core` 被列为 cross-cutting，却导入几乎全部模块。

### 2.2 模块职责、关键接口、输入/输出、实际依赖

下表以 `semantica/<module>/__init__.py` 的公开导出和源码 import 为准；“主要依赖”只列实际出现的同仓模块，不列标准库/第三方。

| 模块 | 关键类/函数（公开接口） | 输入 | 输出 | 主要依赖 |
|---|---|---|---|---|
| `ingest` | `FileIngestor`, `WebIngestor`, `DBIngestor`, `StreamIngestor`, `RepoIngestor`, `EmailIngestor`, `MCPIngestor`, `SnowflakeIngestor`, `DatabricksIngestor`, `SAPIngestor`, `ParquetIngestor`, `XMLIngestor`, `ingest_*()` | path/URL/DSN/stream/IMAP/MCP resource | `FileObject`、`WebContent`、`TableData`、`StreamMessage`、`EmailData` 等**各自类型**，不是统一 `SourceDocument` | `utils`, `provenance` |
| `split` | `TextSplitter`, `EntityAwareChunker`, `RelationAwareChunker`, `GraphBasedChunker`, `OntologyAwareChunker`, `HierarchicalChunker`, `Chunk` | 文本/文档 | `Chunk(text,start_index,end_index,metadata,id)` | `semantic_extract`（惰性，用于 entity/relation 切分）、`provenance`, `utils` |
| `parse` | `DocumentParser`, `StructuredDataParser`, `CodeParser`, `WebParser`, `EmailParser`, `PDFParser`, `DOCXParser`, `PPTXParser`, `ExcelParser`, `XMLParser`, `MediaParser` | 文件路径/字节 | 每种格式一个 `dict`（text、metadata、structure 等） | `utils`, `provenance` |
| `normalize` | `TextNormalizer`, `EntityNormalizer`, `DateNormalizer`, `NumberNormalizer`, `DataCleaner`, `AliasResolver` | 文本/实体/日期/数字 | 规范化后的 `str` / `dict` | `utils`, `provenance` |
| `semantic_extract` | `NERExtractor`, `RelationExtractor`, `EventDetector`, `TripletExtractor`, `CoreferenceResolver`, `Entity`, `Relation`, `Triplet` | 文本/chunk | `Entity(text,label,start_char,end_char,confidence,metadata)`、`Relation`、`Triplet(subject,predicate,object,confidence,metadata)` | `embeddings`, `split`, `provenance`, `utils` |
| `deduplication` | `DuplicateDetector`, `EntityMerger`, `ClusterBuilder`, `MergeStrategyManager` | 实体列表 | 合并后的实体、聚类、merge 结果 | `utils`, `provenance` |
| `conflicts` | `ConflictDetector`, `SourceTracker`, `ConflictResolver`, `ConflictAnalyzer` | 实体/关系/来源 | `Conflict`、`ResolutionResult`、调查指南 | `utils`, `provenance` |
| `kg` | `GraphBuilder`, `KnowledgeGraph`, `TemporalGraphQuery`, `TemporalVersionManager`, `BiTemporalFact`, `CentralityCalculator`, `CommunityDetector`, `PathFinder`, `LinkPredictor` | 文本、`{entities, relationships}`、图 dict | `{"entities": [...], "relationships": [...], "metadata": ...}`；分析结果 | `conflicts`, `context`（惰性）、`deduplication`, `ingest`, `provenance`, `semantic_extract`, `utils` |
| `ontology` | `OntologyEngine`, `OntologyGenerator`, `SHACLGenerator`, `SHACLValidator`, `OWLGenerator`, `OntologyValidator`, `OntologyQualityGate`, `VersionManager` | 图数据/文本/既有本体 | 本体 dict、SHACL shapes/report、OWL 字符串、版本记录 | `change_management`, `ingest`, `kg`, `provenance`, `semantic_extract`, `utils` |
| `embeddings` | `EmbeddingGenerator` + provider | 文本 | 向量 | `provenance`, `utils` |
| `vector_store` | `VectorStore`, `FAISSStore`, `QdrantStore`, `WeaviateStore`, `MilvusStore`, `PineconeStore`, `PgVectorStore`, `HybridSearch`, `DecisionEmbeddingPipeline` | 文本/向量/元数据 | 相似度搜索结果 | `embeddings`, `kg`, `provenance`, `utils` |
| `graph_store` | `GraphStore`, `Neo4jStore`, `FalkorDBStore`, `ApacheAgeStore`, `AmazonNeptuneStore`, `execute_query` | 节点/边/Cypher | CRUD、查询、最短路径 | `context`（惰性）, `provenance`, `utils` |
| `triplet_store` | `TripletStore`, `OxigraphStore`, `BlazegraphStore`, `JenaStore`, `RDF4JStore`, `add_triplet`, `execute_query` | RDF 三元组/SPARQL | 三元组、查询结果 | `provenance`, `semantic_extract`, `utils` |
| `context` | `ContextGraph`, `ContextNode`, `ContextEdge`, `AgentContext`, `AgentMemory`, `Decision`, `Policy`, `DecisionRecorder`, `PolicyEngine`, `CausalChainAnalyzer`, `ContextRetriever`, `TemporalGraphRetriever`, `GroundedContextAssembler` | 图、向量、知识图、决策 | 上下文、决策、记忆、检索结果 | `change_management`, `embeddings`, `evals`, `graph_store`, `kg`, `llms`, `provenance`, `reasoning`, `vector_store`, `utils` |
| `reasoning` | `Reasoner`, `ReteEngine`, `DatalogReasoner`, `SPARQLReasoner`, `TemporalReasoningEngine`, `TruthMaintenanceSession`, `ExplanationGenerator` | facts/rules、三元组、时间区间 | `InferenceResult`、`SPARQLQueryResult`、`Match`、解释 | `kg`, `provenance`, `semantic_extract`, `triplet_store`, `utils` |
| `pipeline` | `PipelineBuilder`, `Pipeline`, `PipelineStep`, `ExecutionEngine`, `ExecutionResult`, `FailureHandler`, `ParallelismManager` | pipeline DSL、数据 | `ExecutionResult` | `provenance`, `utils` |
| `provenance` | `ProvenanceManager`, `ProvenanceEntry`, `InMemoryStorage`, `SQLiteStorage`, `verify_chain`, `export_prov` | entity/relationship/chunk 事件 | `ProvenanceEntry`、lineage、PROV-O 字符串 | `utils` |
| `export` | `RDFExporter`, `JSONExporter`, `OWLExporter`, `CSVExporter`, `ParquetExporter`, `GraphExporter`, `ArrowExporter` | KG dict | Turtle/RDFXML/JSON-LD/NT/OWL/CSV/Parquet 等 | `kg`, `provenance`, `utils` |
| `explorer` | FastAPI `app`、13 个 router、`GraphSession`、`SearchIndex`、React SPA | 图 JSON/实时 session | HTTP JSON、WebSocket、浏览器 UI | `context`, `export`, `kg`, `provenance`, `utils` |
| `core` | `Semantica`, `ConfigManager`, `LifecycleManager`, `PluginRegistry`, `MethodRegistry` | 配置/来源 | `build_knowledge_base()`、`run_pipeline()`、生命周期 | `embeddings`, `ingest`, `kg`, `parse`, `pipeline`, `provenance`, `reasoning`, `utils` |
| `change_management` | `VersionManager`, `OntologyVersionManager`, `ChangeLogEntry`, `InMemoryVersionStorage`, `SQLiteVersionStorage` | 图/本体版本 | 版本记录、快照、diff | `ontology`, `utils` |
| `evals` | `evaluate()` + evaluators | 决策/预测 | `CaseResult`、metrics | `context` |
| `llms` | provider wrappers（OpenAI/Anthropic/Gemini/Groq/Ollama/HuggingFace/LiteLLM） | prompts | 文本/结构化输出 | `provenance`, `semantic_extract` |
| `mcp_server` | stdio JSON-RPC server、22 个 tool handler、3 个 resource | MCP JSON-RPC | tool 结果 | `context`, `export`, `kg`, `reasoning`, `semantic_extract` |
| `benchmarks` | `run_benchmark`, dataset loaders, system adapters | QA 数据集 | 预测/评分 | `context`, `evals` |

> **接口现实**：大多数模块之间传的是 `dict` 和裸类型，不是正式 Protocol。`semantica/utils/types.py` 定义了 `Processor` / `Validator` / `Exporter` / `Importer` 四个 `Protocol`，但核心路径（GraphBuilder、ContextGraph、DecisionRecorder）并不依赖它们。真正稳定的扩展点是 `MethodRegistry` 和 `PluginRegistry`（见 §2.4）。

### 2.3 实际依赖：循环与分层泄漏

用源码 import 统计后，至少存在以下**双向依赖**：

| 循环 | 证据 |
|---|---|
| `context ↔ kg` | `context/context_retriever.py:87-90` 直接 import `kg.path_finder` / `kg.centrality_calculator` / `kg.community_detector` / `kg.similarity_calculator`；`kg/methods.py:1117-1124` 惰性 import `context.methods`，`kg/registry.py:272-279` 惰性 import `context.global_retriever` / `context.drift_search` |
| `context ↔ graph_store` | `context/policy_engine.py:74`、`context/decision_recorder.py:79`、`context/causal_analyzer.py:67` import `GraphStore`；`graph_store/graph_store.py:1174` 惰性 import `context.entity_linker` |
| `split ↔ semantic_extract` | `split/methods.py:139-141` import `semantic_extract.ner_extractor` / `providers` / `relation_extractor`；`semantic_extract/methods.py:1439`、`:2421`、`:2896` 惰性 import `split.TextSplitter`（entity-aware / semantic chunking） |
| `ontology ↔ change_management` | `ontology/engine.py:36` import `VersionManager`；`change_management/ontology_version_manager.py:45` import `ontology.namespace_manager` |

这不是“clean interfaces / no hidden coupling”。它意味着：

- 不能按官方四层直接切 Rust crate；`context` 与 `kg` 的边界需要重新设计，否则会出现 `ContextGraph` 依赖 `KG` 算法、`KG` 又依赖 `ContextGraph` 的类型。
- `split` 依赖抽取器、抽取器又依赖 `split`，说明“切分”和“抽取”的语义耦合很深。heirloom 若要“实体感知切分 + 可插拔 Python 抽取”，需要把切分策略与抽取器接口解耦，而不是复刻这种双向 import。
- `ontology` 与 `change_management` 互相依赖，版本管理不是独立的横切层。

### 2.4 扩展点：`MethodRegistry` 与 `PluginRegistry`

- `MethodRegistry`：类级字典，按 task（`pipeline` / `knowledge_base` / `orchestration` / `lifecycle`）注册函数；`register/get/list_all/unregister/clear`。各模块也有自己的 `method_registry`（如 `ingest`、`parse`、`normalize`、`split`、`semantic_extract`）。【[core/registry.py:38-120](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/core/registry.py#L38-L120)】
- `PluginRegistry`：从文件系统发现插件、检查依赖、加载/卸载/清理，记录 `PluginInfo(name, version, plugin_class, dependencies, capabilities, metadata)`。【[core/plugin_registry.py:31-120](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/core/plugin_registry.py#L31-L120)】

**对 heirloom 的启示**：Semantica 的扩展点是“运行时注册函数/类”，没有编译期 trait 约束，也没有版本化接口描述。Rust 核心若用 trait + 版本化 manifest，能在编译期保证插件符合契约；Python 处理层可以用 entry points/Protocol，而不是全局可变 registry（全局 registry 在测试和多租户场景容易串状态）。

---

## 3. 核心对象模型

### 3.1 `ContextGraph`

**字段（构造时）**：`graph_id: str`、`nodes: Dict[str, ContextNode]`、`edges: List[ContextEdge]`、`_edge_index: Dict[str, ContextEdge]`、`_adjacency: Dict[str, List[ContextEdge]]`、`node_type_index`、`edge_type_index`、`_linked_graphs`、`_unresolved_links`、`_retractions`、`_tombstones`、`kg_components`、`mutation_callback`、`_lock: threading.RLock`。【[context_graph.py:584-672](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L584-L672)】

**决策相关字段（惰性创建）**：`_decisions: Dict[str, dict]`、`_decision_index: Dict[category, Set[id]]`、`_entity_index: Dict[entity, Set[id]]`、`_temporal_index: List[(id, epoch)]`。【[context_graph.py:4607-4617](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L4607-L4617)】

**生命周期**：

1. `ContextGraph(config)`：初始化索引、RLock、可选 KG 组件（centrality/community/node embeddings/path finder/similarity/connectivity）。
2. `add_node(s)` / `add_edge(s)`：写入节点/边；`add_edge` 校验 SKOS 层级环；不存在的端点会自动建 `entity` 节点。
3. 查询/分析：`find_nodes`、`find_edges`、`neighbors`、`get_neighbors`、`state_at`、`stats`、`density`、centrality/community 等。
4. 决策：`record_decision` / `add_decision` / `add_causal_relationship` / `find_precedents_by_scenario` / `analyze_decision_influence` / `trace_decision_causality` / `enforce_decision_policy`。
5. 撤回/清除：`retract_node/edge` 关闭 `valid_until` 并保留记录；`purge_node/edge` 删除内容但写 tombstone；`clear()` 清空。
6. 持久化：`save_to_file()` / `load_from_file()`，支持 JSON 与 Markdown 目录；`to_dict` / `from_dict` / `to_kg_dict`。
7. 线程安全：所有写路径基本在 `RLock` 内；读路径部分加锁，部分直接访问索引。

**关键语义缺陷**：

- `ContextGraph` 没有自动持久化；`save_to_file` 是整图快照，不是 WAL/增量。
- `ContextGraph` 没有事务；`_add_decision_to_graph` 做手工回滚（记录 `nodes_before` / `edges_before`）。
- `ContextGraph` 不区分“事实”和“实体”：任何 `node_type` 都只是字符串，`Decision` 也只是 `node_type="Decision"`。
- 决策的“索引”与节点存储是两套状态；`add_node` 直接写节点不会同步 `_decisions`，只有 `add_decision` / `record_decision` / `_rebuild_decision_indexes` 才同步。

### 3.2 `ContextNode` / `ContextEdge`

| 对象 | 字段 | 方法/语义 |
|---|---|---|
| `ContextNode` | `node_id: str`、`node_type: str`、`content: str`、`metadata: dict`、`properties: dict`、`valid_from: str?`、`valid_until: str?` | `is_active(at_time)`；`to_dict()` 把 `properties` 与 `metadata` 合并，`content` 放进 `properties["content"]` |
| `ContextEdge` | `source_id`、`target_id`、`edge_type`、`edge_id`、`weight: float`、`family_id`、`metadata`、`valid_from`、`valid_until` | `__post_init__` 规范化 id/weight/metadata；`is_active`；`to_dict()` |
| 边身份 | `edge_id` 由 source/target/type/weight/metadata/validity 内容派生；`family_id` 用于同一关系族的版本 | 代码注释明确 `edge_id` 不保证唯一（#922），retraction 需要额外处理 |

来源：`context_graph.py:420-530`。

**重要细节**：`ContextNode.to_dict()` 把 `metadata` 和 `properties` 合并；`add_node()` 把 `**properties` 同时写入 `metadata` 和 `properties`，所以二者通常相同。`ContextEdge.to_dict()` 把 `metadata` 原样放在 `"properties"` 下，不是顶层；这会影响 `TemporalGraphQuery` 读取 `recorded_at`（见 §5.4）。

### 3.3 `Decision` 及关联模型

`Decision`（dataclass）字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `decision_id` | `str` | 必填；空字符串/None 时自动 UUID |
| `category` | `str` | 决策类别 |
| `scenario` | `str` | 情景描述；同时作为节点 `content` |
| `reasoning` | `str` | 推理说明 |
| `outcome` | `str` | 结果 |
| `confidence` | `float` | 0–1；`__post_init__` 校验 |
| `timestamp` | `datetime` | 决策时间 |
| `decision_maker` | `str` | 决策者 |
| `reasoning_embedding` | `List[float]?` | 语义嵌入 |
| `node2vec_embedding` | `List[float]?` | 图嵌入 |
| `valid_from` / `valid_until` | `str?` | 有效时间窗口 |
| `metadata` | `dict` | 扩展字段 |

来源：`decision_models.py:87-150`。

关联模型：

| 模型 | 字段 | 用途/限制 |
|---|---|---|
| `DecisionContext` | `context_id`, `decision_id`, `entity_snapshots`, `risk_factors`, `cross_system_inputs`, `metadata` | 定义了“决策时上下文快照”，但 `record_decision` 不自动创建；`DecisionRecorder.capture_cross_system_context` 只支持 Cypher graph store，ContextGraph 路径会失败 |
| `Policy` | `policy_id`, `name`, `description`, `rules`, `category`, `version`, `created_at`, `updated_at`, `metadata` | 版本化策略；规则是自由 dict |
| `PolicyException` | `exception_id`, `decision_id`, `policy_id`, `reason`, `approver`, `approval_timestamp`, `justification`, `metadata` | 例外审批 |
| `Precedent` | `precedent_id`, `source_decision_id`, `similarity_score`, `relationship_type`（`similar_scenario` / `same_policy` / `exception_precedent`）, `metadata` | 先例关系 |
| `ApprovalChain` | `approval_id`, `decision_id`, `approver`, `approval_method`（`slack_dm` / `zoom_call` / `email` / `system`）, `approval_context`, `timestamp`, `metadata` | 审批链；ContextGraph 路径不支持持久化 |

来源：`decision_models.py:152-360`；`decision_recorder.py:432-520`。

### 3.4 `Fact`：没有一等公民

Semantica 里的 “fact” 至少有四种含义，彼此没有统一 ID：

1. **图事实**：`ContextEdge`（source/target/type/weight/metadata），可带 `valid_from/valid_until`；`ContextNode` 也可带时间窗口。没有 `fact_id`，边身份由内容派生。
2. **双时态事实包装**：`BiTemporalFact(valid_from, valid_until, recorded_at, superseded_at)`，只用于把关系 dict 规范化；它明确写着 “Facts continue to live as plain relationship dicts in the graph. This wrapper is only used internally for normalization”。【[temporal_model.py:28-40](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/kg/temporal_model.py#L28-L40)】
3. **推理事实**：`reasoning.Fact(fact_id, predicate, arguments, metadata)`，只存在于 working memory；`Reasoner` 里的事实是字符串（`"Predicate(arg)"`）。【[reasoner.py:339-347](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/reasoning/reasoner.py#L339-L347)】
4. **溯源条目**：`ProvenanceEntry(entity_id, entity_type, activity_id, ...)`，以 `entity_id` 为主键，不是“事实 ID”。【[schemas.py:37-190](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/provenance/schemas.py#L37-L190)】

**结论**：Semantica 没有“Fact = 带 id、polarity、confidence、valid time、transaction time、provenance、derived_from 的一等对象”。这对 heirloom 是最大的模型机会：如果蓝图里要“决策/事实/溯源”三者可审计地连起来，必须在 Rust 核心里定义 `Fact` 的规范身份与生命周期，而不是沿用“边就是事实”的隐式模型。

### 3.5 `ProvenanceEntry`

字段分组（完整字段见 `schemas.py:37-190`）：

| 组 | 字段 |
|---|---|
| PROV 核心 | `entity_id`, `entity_type`, `activity_id`, `agent_id`, `agent_type`, `is_automated`, `role` |
| 来源 | `source_document`, `source_location`, `source_quote` |
| 时间 | `timestamp`, `first_seen`, `last_updated`, `activity_started_at_time`, `activity_ended_at_time` |
| 质量/完整性 | `confidence`, `checksum`, `checksum_version`, `sequence_id`, `previous_checksum` |
| 派生/版本 | `parent_entity_id`, `used_entities`, `previous_version_id`, `derived_from_id`, `supersedes`, `revision_type` |
| 双时态 | `valid_from`, `valid_until`（事实有效时间） |
| 失效 | `invalidated`, `invalidated_at_time`, `invalidated_by`, `invalidation_reason` |
| 分桶/分块 | `bundle_id`, `start_index`, `end_index` |
| 其他 | `credibility`, `metadata`, `version` |

**生命周期**：

1. `track_entity()` / `track_relationship()` / `track_chunk()` / `track_property_source()` 写入一条 entry；`track_entity` 是 upsert 语义，更新时把旧版本归档成 `entity_id:v:<timestamp>` 历史行，并设置 `previous_version_id`。
2. `invalidate()` 不删除行，而是写一条失效记录/tombstone（`invalidated=True` 等）。
3. `get_lineage()` / `trace_lineage()` / `trace_descendants()` / `revision_history()` 查询。
4. `export_prov()` 输出 Turtle/N-Triples/JSON-LD；`verify_chain()` 校验哈希链。
5. 存储：默认 `InMemoryStorage`；传 `storage_path` 用 SQLite；SQLite 主键是 `entity_id`。

来源：`manager.py:291-478`、`:809-1164`、`:1294-1456`、`:1506-1610`；`storage.py:580-640`。

### 3.6 `Ontology`

Semantica 没有一个 `Ontology` 类；本体是普通 dict：

```text
{
  "uri": ..., "name": ..., "version": ...,
  "classes": [...], "properties": [...],
  "metadata": {...}
}
```

`OntologyGenerator.generate_ontology()` 的 5 阶段流水线：语义网络解析 → YAML-to-definition → definition-to-types（OWL 类型）→ 层级生成 → TTL 生成（由 `OWLGenerator` 完成）。输出字段见 `ontology_generator.py:147-155`。

SHACL 侧有独立模型：`SHACLGraph(base_uri, shapes_uri, node_shapes, prefixes, class_iris, property_iris)`、`NodeShape`、`PropertyShape`、`SHACLValidationReport`、`SHACLViolation`。SHACL 验证调用 `pyshacl.validate(..., inference="none")`，是真实实现。【[ontology_generator.py:793-806](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/ontology/ontology_generator.py#L793-L806)】【[ontology_validator.py:276-397](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/ontology/ontology_validator.py#L276-L397)】

OWL 侧：`OWLGenerator` 用 rdflib 生成 `owl:Ontology`、`owl:Class`、`owl:ObjectProperty`、`owl:DatatypeProperty`、`rdfs:subClassOf`、`rdfs:domain`、`rdfs:range`、`rdfs:label/comment`、`owl:versionInfo` 等基础公理；没有 OWL 2 profile、没有 `equivalentClass`/`disjointWith`/restrictions 等 DL 公理，也没有 DL 一致性检查。`OntologyValidator` 的 `reasoner="hermit"` 参数是占位实现：源码注释写明 “Placeholder implementation for now / In a real implementation, this would load owlready2 or similar”，并跳过 consistency/satisfiability 检查。【[owl_generator.py:216-335](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/ontology/owl_generator.py#L216-L335)】【[ontology_validator.py:299-370](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/ontology/ontology_validator.py#L299-L370)】

版本：`OntologyVersion(version, ontology_iri, version_info, created_at, changes, metadata)`；`VersionManager.create_version()` 生成带版本号的 ontology IRI，元素 IRI 保持无版本以稳定引用。【[ontology_version_manager.py:49-148](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/change_management/ontology_version_manager.py#L49-L148)】

### 3.7 Temporal

| 对象 | 字段/接口 | 说明 |
|---|---|---|
| `TemporalInterval` | `start: datetime`, `end: datetime \| TemporalBound`, `label` | 不可变 dataclass |
| `IntervalRelation` | 13 个枚举：`BEFORE/AFTER/MEETS/MET_BY/OVERLAPS/OVERLAPPED_BY/STARTS/STARTED_BY/DURING/CONTAINS/FINISHES/FINISHED_BY/EQUALS` | Allen 代数 |
| `TemporalReasoningEngine` | `relation`, `overlaps`, `contains`, `active_at`, `merge_intervals`, `gap_analysis`, `coverage_percentage`, `timeline_of`, `retroactive_coverage`, `normalize_timestamp` | 纯 Python、无 LLM |
| `BiTemporalFact` | `valid_from`, `valid_until`, `recorded_at`, `superseded_at` | 关系 dict 的包装器 |
| `TemporalGraphQuery` | `query_at_time`, `reconstruct_at_time`, `query_time_range`, `validate_temporal_consistency`, `query_temporal_pattern`, `analyze_evolution`, `find_temporal_paths` | 支持 `time_axis="valid" / "transaction" / "both"` |
| `TemporalVersionManager` | `create_version`, `compare_versions`, `create_snapshot`, `apply_revision`, `list_versions`, `get_version`, `validate_snapshot`, `migrate_snapshot`, `verify_checksum` | 全图快照 + SHA-256 |
| `TemporalPatternDetector` | `detect_temporal_patterns` | sequence/cycle |
| `TruthSnapshotProvider` | 捕获不可变 session/bitemporal read view | grounded context 用 |

来源：`temporal_reasoning.py:24-120`、`temporal_model.py:28-65`、`temporal_query.py:41-169`、`:1159-1415`。

### 3.8 Pipeline

| 对象 | 字段 |
|---|---|
| `PipelineStep` | `name`, `step_type`, `config`, `dependencies`, `handler`, `status`（`PENDING/RUNNING/COMPLETED/FAILED/SKIPPED`）, `result`, `error`, `delta_mode`, `base_version_id`, `target_version_id`, `parallel_safe` |
| `Pipeline` | `name`, `steps`, `config`, `metadata` |
| `ExecutionResult` | `success`, `output`, `metadata`, `metrics`, `errors` |
| `ExecutionEngine` | `execute_pipeline`, `pause`, `resume`, `stop`, status map, resource scheduler, failure handler, parallelism manager |

生命周期：`PipelineBuilder.add_step/connect_steps/set_parallelism/build` → `ExecutionEngine.execute_pipeline` → 状态/结果；支持 `delta_mode`（增量）、`parallel_safe`、retry/fallback。来源：`pipeline_builder.py:56-230`、`execution_engine.py:53-200`。

### 3.9 `AgentMemory`

| 对象 | 字段 |
|---|---|
| `MemoryItem` | `content`, `timestamp`, `metadata`, `entities`, `relationships`, `embedding`, `memory_id` |
| `AgentMemory` | `vector_store`, `knowledge_graph`, `retention_policy`, `max_memory_size`, `short_term_limit`, `token_limit`, `memory_items`, `memory_index`（deque）, `short_term_memory`（list）, `_vector_ids` |

生命周期：

1. `store(content, metadata, entities, relationships)`：写 short-term buffer（默认 10 条 / 2000 token）→ vector store（可选）→ `memory_items` / `memory_index` → 若传了 `knowledge_graph` 且有 entities，则更新图（只写实体/关系，**不把 memory 本身作为图节点**）→ retention。
2. `retrieve(query)`：short-term 关键词 → vector store 相似度 → 无结果时 keyword fallback；按 score 排序。
3. `update` / `delete_memory` / `forget` / `clear`；`save` / `load` JSON；`export` / `import_data` Markdown/JSON。
4. retention：`retention_policy="N_days"` 或 `"unlimited"`；默认 `max_memory_size=10000`。

来源：`agent_memory.py:128-245`、`:323-585`、`:1075-1105`。

### 3.10 对象模型总表

| 对象 | 是否一等公民 | 存储形态 | 身份 | 生命周期 |
|---|---|---|---|---|
| `ContextGraph` | 是 | 内存 dict/list + RLock；整图 JSON/Markdown | `graph_id` UUID | create → mutate → query → save/load → clear |
| `ContextNode` | 是 | `ContextGraph.nodes` | `node_id`（调用方提供） | add → update attr → retract/purge |
| `ContextEdge` | 是 | `ContextGraph.edges` + index | `edge_id`（内容派生）/`family_id` | add → retract/purge |
| `Decision` | 是（作为节点） | `_decisions` dict + Decision 节点 | `decision_id` UUID | record → link → query → retract/purge |
| `Fact` | **否** | 边/关系 dict，或 `BiTemporalFact` 包装 | 无统一 ID | 随边生命周期 |
| `ProvenanceEntry` | 是（独立存储） | InMemory/SQLite；`entity_id` 主键 | `entity_id` | track → revise/invalidate → export/verify |
| `Ontology` | **否**（dict） | Python dict；版本记录独立 | `uri` + `version` | generate → validate → SHACL → version → export |
| `TemporalInterval` / `BiTemporalFact` | 是（值对象） | 内存 | 无 | 计算/包装 |
| `Pipeline` / `PipelineStep` | 是（配置对象） | 内存/序列化 | `name` / `step.name` | build → execute → status |
| `AgentMemory` | 是 | 内存 dict/deque + vector store | `memory_id` UUID | store → retrieve → update/delete → retention |

---

## 4. 决策智能：语义与限制

### 4.1 `record_decision()` 的实际流程

`ContextGraph.record_decision()` 的签名：

```python
record_decision(category, scenario, reasoning, outcome, confidence,
                entities=None, decision_maker=None, metadata=None,
                valid_from=None, valid_until=None, decision_id=None, **kwargs) -> str
```

流程：

1. **输入校验**（见 §4.2）。
2. 生成/校验 `decision_id`；`timestamp = datetime.now().timestamp()`（**float epoch**）；`recorded_at = datetime.utcnow().isoformat()`。
3. 组装 decision dict：`id/category/scenario/reasoning/outcome/confidence/entities/decision_maker/timestamp/recorded_at/valid_from/valid_until/metadata/**kwargs`。
4. `_add_decision_to_graph()`：
   - 新增 `Decision` 节点（`content=scenario`，properties 含 category/outcome/confidence/timestamp/scenario/decision_maker/reasoning/recorded_at + metadata + extra）；
   - 对每个 entity：不存在则建 `entity` 节点，新增 `Decision -[involves]-> entity`；
   - 新增 `category_<category>` 节点，`Decision -[belongs_to]-> category`；
   - 若有 decision_maker：新增 `maker_<decision_maker>` 节点，`Decision -[made_by]-> maker`；
   - 任一步失败，按 `nodes_before` / `edges_before` 手工回滚。
5. 写入 `_decisions[decision_id]`、`_decision_index[category]`、`_entity_index[entity]`、`_temporal_index`。
6. 返回 `decision_id`。

来源：`context_graph.py:4450-4617`、`:5123-5255`。

**关键含义**：

- 一条决策至少产生 4 类节点和 3 类边（实体多则更多），写入放大明显。
- 决策不接收 `source_documents`、证据 ID、活动 ID、agent 类型；`Decision` 模型也没有这些字段。因此“决策 → 证据/来源”的链接需要调用方自己塞进 `metadata`，或另用 `ProvenanceManager`（但见 §5.1 的接线缺陷）。
- `valid_from/valid_until` 只影响决策节点和后续时间过滤；`recorded_at` 只是节点属性，不参与 `ContextGraph` 查询。

### 4.2 `record_decision()` 的校验限制

源码硬编码的限制，对 heirloom 的 API/schema 设计有直接参考价值：

| 字段 | 限制 |
|---|---|
| `category` | 非空字符串，≤100 字符 |
| `scenario` | 非空字符串，≤5000 字符 |
| `reasoning` | 非空字符串，≤10000 字符 |
| `outcome` | 非空字符串，≤1000 字符 |
| `confidence` | 数值，0.0–1.0 |
| `entities` | list[str]；每项非空，≤200 字符 |
| `decision_maker` | 非空字符串，≤200 字符 |
| `metadata` | dict；key 非空 ≤100 字符，value `str()` ≤1000 字符 |
| `**kwargs` | key 非空 ≤100 字符，value `str()` ≤1000 字符；`id` 保留，不能覆盖 |
| `decision_id` | 非空字符串；不能与已有节点/决策重复 |

来源：`context_graph.py:4465-4558`。

> 这些限制意味着 **Semantica 的决策记录不适合直接存大段证据、JSON 证据包或多模态引用**；它更像“结构化摘要 + 元数据”。heirloom 若要把证据作为一等对象，需要单独的 evidence/artifact 表或对象存储引用。

### 4.3 因果链

**显式因果边**：

- `add_causal_relationship(source_decision_id, target_decision_id, relationship_type)`：
  - 只接受 `CAUSED` / `INFLUENCED` / `PRECEDENT_FOR`，以及别名 `CAUSES` / `INFLUENCES` / `PRECEDES`；
  - 两个端点必须已存在且 `node_type.lower() == "decision"`；
  - 重复边返回 `False` 并 warning，不抛异常；非法类型抛 `ValueError`；
  - 边 metadata 记录 `recorded_at`。
- `get_causal_chain(decision_id, direction="upstream"/"downstream"/"both", max_depth=10)`：对 `_CAUSAL_TRAVERSAL_TYPES`（`CAUSED/INFLUENCED/PRECEDENT_FOR/LEADS_TO/SUPPORTS` 及别名）做 BFS；跳过 inactive 边/节点；返回 `Decision` 对象，`metadata["causal_distance"]` 记录深度。

来源：`context_graph.py:3934-4006`、`:4008-4133`。

**`trace_decision_causality()` 的混合语义**：

- 先走显式因果边（`incoming_causal_edges`）；
- 再找“共享实体且时间更早”的决策，作为 `type="influences"` 的潜在原因；
- 递归枚举所有路径，`max_chains` 默认 10000，超过则追加 `{"truncated": True, ...}`；
- 每条链路返回 `hop_count`、`confidence_decay`（边 weight 连乘）、`weakest_link`、`distance_band`、`interpretation`。

来源：`context_graph.py:4889-5067`、`:5721-5758`。

**问题**：调用方看到的是统一的 `causal_chain`，但其中一部分是**显式断言**，另一部分是**共享实体 + 时间顺序的启发式**。对于审计/合规场景，“heuristic influences” 不能和 “CAUSED” 混在一个字段里。heirloom 若实现类似能力，应该至少用 `asserted` vs `inferred` 标记，或把两者拆成不同 API。

### 4.4 Precedent search

`find_precedents_by_scenario(scenario, category=None, limit=10, similarity_threshold=0.5, use_semantic_search=True, include_superseded=False, as_of=None, soft_floor=None, include_neighbors=0, **filters)`：

1. 候选集：按 category 取 `_decision_index`，否则全部 `_decisions`；可用 `entities` 过滤。
2. 若 `advanced_analytics`，为每个候选计算结构分 `structural_scores`（基于节点类型和出度的“度数邻域”分数）。
3. 对每个候选：
   - `content_sim = _calculate_decision_content_similarity(scenario, decision)`；
   - `structural_sim = structural_scores[decision_id]`；
   - `combined_sim = 0.7 * content_sim + 0.3 * structural_sim`。
4. 过滤 `combined_sim >= (soft_floor if provided else similarity_threshold)`，排序取 top-k。
5. `include_neighbors`：先附显式因果邻居，再附共享实体邻居。

**`content_sim` 的具体算法**：

- 词级 Jaccard：`scenario_words` vs `decision.scenario`（主信号）；`scenario + reasoning + entities` 的全文本 Jaccard × 0.8（次信号）；取 max。
- 若查询是 CJK/日韩文字，或只有一个 whitespace token，则用字符 bigram Jaccard；bigram 数少于 3 不启用。
- 异常时返回 0.0。

来源：`context_graph.py:5521-5635`。

**结构分的具体含义**：`_precedent_structural_scores` 统计同类型节点的出度分布；对每个候选，如果同类型同出度的节点不止一个，得 1.0；否则用最近邻出度的相对差值得分。它**不是候选与查询的结构相似度**，而是一个“该决策在图中是否处于典型度数”的先验。来源：`context_graph.py:5636-5677`。

**结论**：

- `use_semantic_search` 是**无效参数**；没有任何 embedding/vector store 调用。
- `find_precedents()` / `find_similar_decisions()` 的“semantic”宣传不成立；官方决策指南在正文里承认是 lexical，但 README/MCP 文档仍写 semantic/hybrid。
- 默认阈值 0.5（`find_precedents_by_scenario`）与 0.3（`find_similar_decisions`）不一致；`soft_floor` 是后续为修正“精确场景因 reasoning 太长被稀释”（#1140）而加的排序语义。
- 结构分占 30%，会让“高度数节点”在不相关查询中排名上升；它是启发式，不是语义相关性。

### 4.5 Impact analysis

`analyze_decision_influence(decision_id, max_depth=3, include_indirect=True)`：

1. **直接“影响”**：
   - 与源决策共享任意实体的所有决策；
   - 与源决策同 category 的所有决策；
   - 与源决策有显式因果边（任一方向）的决策。
2. **间接“影响”**：若 `advanced_analytics`，从源决策出发做图遍历（`_find_indirect_decision_influence`），减去直接集。
3. **影响分**：`0.5 * entity_score + 0.3 * category_score + 0.2 * time_score`；`entity_score = |shared entities| / |source entities|`，`category_score` 为 0/1，`time_score = max(0, 1 - |Δt| / 30 days)`。
4. 返回 `direct_influence`、`indirect_influence`、`influence_scores`（含 breakdown）、`total_influenced`、`max_influence_score`。

来源：`context_graph.py:4741-4835`、`:5759-5796`。

**问题**：

- “同 category 即直接影响”没有方向、时间、证据或干预含义；一个大类别会把所有同类决策都算进来。
- “共享任意实体即直接影响”会把共同提及同一客户/同一供应商的决策当作互相影响。
- 间接影响是普通图可达性，不是因果路径；没有排除混杂、没有反事实。
- `max_depth` 只在间接集使用；直接集不受深度限制。

### 4.6 Policy gate

Semantica 有**两个不同的策略门**，不要混淆：

#### A. `PolicyEngine`（版本化策略节点）

- `PolicyEngine(graph_store)`；`add_policy(Policy)` 把策略写成图节点；`get_policy(policy_id, version)`；`check_compliance(decision, policy_id) -> bool`；`record_policy_application`；`record_exception`；`get_policy_history`；`analyze_policy_impact`。
- 支持规则：
  - `min_*`：metadata 字段 ≥ 值；`min_confidence` 也走这条（`getattr(decision, "confidence")`）；
  - `max_*`：metadata 字段 ≤ 值（字符串按字典序）；
  - `required_*`：metadata 字段等于值（str）或包含所有项（list）；
  - `allowed_outcomes`：outcome 必须在列表；
  - `required_categories`：**实现有 bug**，见下；
  - 未知 key：只检查该 key 是否存在于 `metadata`，存在即通过（不管值）。
- `check_compliance` 返回 `False` 表示“评估过且不合规”；评估本身失败（如规则值类型错误）抛 `ProcessingError`，避免把执行失败伪装成不合规。
- **不做自动阻断**；是否阻断由调用方决定。

来源：`policy_engine.py:80-418`、`:916-1048`；文档 `docs/guides/policy-engine.md:60-90`、`:335`。

**`required_categories` bug**：`_evaluate_compliance` 先匹配 `required_` 前缀，把 `required_categories` 解析成字段 `"categories"`；`_get_metadata_field(metadata, decision, "categories")` 查 `metadata["categories"]`、后缀匹配、最后 `getattr(decision, "categories", None)`。`Decision` 的字段是单数 `category`，没有 `categories`，所以通常返回 `None`，直接 `return False`。也就是说，文档宣称支持的 `required_categories` 在正常 `Decision` 上**永远不合规**，除非 metadata 里恰好有 `categories`。来源：`policy_engine.py:906-958`；`decision_models.py:87-100`；文档 `docs/guides/policy-engine.md:73`。

#### B. `ContextGraph.check_decision_rules()` / `enforce_decision_policy()`

- 这是另一个更简单的实现，默认规则：
  - `min_confidence: 0.7`
  - `required_outcomes: ["approved", "rejected", "flagged"]`
  - `required_metadata: ["decision_maker"]`
  - `max_reasoning_length: 10000`
- 返回 `{compliant, violations, warnings, policy_rules}`；**没有策略版本、没有图节点、没有审计记录**。
- README 的 quick start 用的就是它，并明确说“checks the fields you pass against the default rules”。

来源：`context_graph.py:5067-5122`、`:6057-6075`；`README.md:145-155`。

**问题**：

- 两套策略语义、两套规则名、两套返回类型；调用方很容易以为 `check_decision_rules` 会使用 `PolicyEngine` 的版本化策略。
- `check_decision_rules` 的默认 outcome 白名单是 `approved/rejected/flagged`；业务 outcome（如 `selected_aws`、`rate_set_8.9pct`）会被判不合规，除非传自定义 rules。
- `PolicyEngine` 的 `required_categories` bug 会让策略无解释地失败。
- 审批链/例外只在 graph store（Cypher）路径完整；ContextGraph 的 `record_approval_chain` 没有 fallback。

### 4.7 决策智能限制清单

| 限制 | 证据 | 对 heirloom 的含义 |
|---|---|---|
| 无证据/来源字段 | `Decision` / `record_decision` 没有 source_documents；只有 entities + metadata | 必须单独建 evidence/artifact 关系 |
| 无自动溯源 | `ContextGraph` 无 provenance 字段；`DecisionRecorder` 路径接线错误 | 溯源必须在写入路径强制，不可选 |
| 因果关系混合 | `trace_decision_causality` 把共享实体+时间顺序当 `influences` | 显式因果与推断因果必须分字段/分 API |
| 影响分析是启发式 | 同类别/共享实体/图可达性都算影响 | 如果要“影响”，定义可审计的因果模型或标注 heuristic |
| Precedent 是词面 | `use_semantic_search` 无效；Jaccard + 度数先验 | 语义检索应由 Python/向量层显式提供，不能依赖该标志 |
| 策略 DSL 很窄 | 只支持 `min_/max_/required_/allowed_outcomes/required_categories`；无嵌套/OR/否定 | 需要自己的规则语言/执行器，或明确只做简单 gate |
| 审批链依赖 Cypher | `record_approval_chain` 只走 `execute_query` | 内存/嵌入式路径需要独立 workflow store |
| 决策可变 | `add_node_attribute` 可改决策节点；`retract/purge` 可改变历史 | 决策日志应 append-only，修正用新版本/新事件 |
| 无置信度传播 | 决策 confidence 是单值；没有证据置信度合成 | 若需要“证据 → 决策”置信度，需定义传播规则 |

---

## 5. 溯源与时序

### 5.1 PROV-O 映射

`ProvenanceEntry` 的 docstring 给出了明确的映射表；`export_prov()` 按这个映射生成 RDF：

| `ProvenanceEntry` 字段 | PROV-O 术语 | 实现说明 |
|---|---|---|
| `entity_id` | `prov:Entity` | `EX[entity_id]` |
| `activity_id` | `prov:Activity` | `EX[activity_id]` |
| `agent_id` / `agent_type` | `prov:Agent` / `prov:Person` / `prov:SoftwareAgent` / `prov:Organization` | `_AGENT_TYPE_PROV_CLASS` 映射 |
| `role` | `prov:hadRole`（经 `prov:qualifiedAssociation`） | 生成 `prov:Association` blank node |
| `parent_entity_id` / `derived_from_id` | `prov:wasDerivedFrom` | 同时生成 `prov:qualifiedDerivation` |
| `previous_version_id` | “prior version of same fact” | **没有在 `export_prov` 里导出为 `prov:wasRevisionOf`**；只在内部 revision 链使用 |
| `used_entities` | `prov:used` | 同时生成 `prov:qualifiedUsage` |
| `timestamp` | `prov:generatedAtTime` | `xsd:dateTime` |
| `activity_started_at_time` / `activity_ended_at_time` | `prov:startedAtTime` / `prov:endedAtTime` | 类型化 Activity |
| `acted_on_behalf_of` | `prov:actedOnBehalfOf` | Agent 委派 |
| `informed_by_activities` | `prov:wasInformedBy` | Activity 链 |
| `invalidated*` | `prov:Invalidation` | `prov:qualifiedInvalidation`，tombstone 不删行 |
| `bundle_id` | `prov:Collection` / `prov:Bundle` | 只是 membership triple，不是 named graph |
| `source_document` / `source_location` / `source_quote` | 无直接 PROV 术语 | 作为实体字段/审计信息，不在 RDF 中逐条映射 |

来源：`provenance/schemas.py:11-22`、`provenance/manager.py:1294-1456`。

**优点**：这是 Semantica 里少数“按标准认真做”的部分。qualified association/derivation/generation/usage/invalidation 都有，`agent_type` 也区分 Person/SoftwareAgent/Organization；`verify_chain()` 还提供哈希链完整性校验。

**缺口**：

1. **决策/图事实没有自动 PROV 记录。** `export_prov()` 只导出 `ProvenanceManager` 里已有的 entry；`ContextGraph.to_kg_dict()` 不含 provenance；`DecisionRecorder` 的接线又坏了（见 §5.2）。所以“record_decision() 产生的记录可直接导出为 PROV-O”在默认路径上不成立；README 的 audit recipe 是手工 `prov.track_entity(...)` + 单独 `RDFExporter().export(kg, ...)`，不是同一个 PROV 图。
2. **修正关系缺失。** `previous_version_id` 表示“同一事实的上一版本”，但 `export_prov` 没有 `prov:wasRevisionOf`；审计者无法从 RDF 直接看出“这条记录修正了哪条”。
3. **`prov:Bundle` 不是 named graph。** 只是 `prov:hadMember` membership triple；不能按 bundle 做 RDF dataset 隔离。
4. **没有 `prov:Plan`、`prov:wasAssociatedWith` 的完整活动模型**（有 `wasAssociatedWith`，但没有 plan/role 的完整建模）。
5. **`source_document` 是单值**；一条 entry 不能表达“多来源 corroboration”。`get_all_sources()` 是从历史/多行里聚合，不是同一 entry 的多来源集合。

### 5.2 溯源生命周期与已知缺陷

**生命周期**：

1. `track_entity(entity_id, source, metadata=None, **kwargs)`：
   - 开事务（SQLite `BEGIN IMMEDIATE`）；
   - 若已有 entry，把旧 entry 归档为 `entity_id:v:<last_updated>` 历史行；
   - 生成新 `ProvenanceEntry`，写 `sequence_id = head + 1`、`previous_checksum`；
   - `_save_entry` 计算 checksum 并写入。
2. `track_relationship` / `track_chunk` / `track_property_source` 类似，只是 `entity_type` 不同。
3. `invalidate(entity_id, ...)`：写一条 revision，设置 `invalidated=True`、`invalidated_at_time`、`invalidated_by`、`invalidation_reason`；不物理删除。
4. `get_lineage` / `trace_lineage` / `trace_descendants` / `revision_history`。
5. `export_prov(format="turtle"|"ntriples"|"jsonld")`；`verify_chain(expected_count, expected_head)`。

**哈希链**：`sequence_id` 全局递增，`previous_checksum` 链接前一条；`verify_chain()` 检查每条 checksum、前驱 checksum、sequence 连续性。`compute_checksum` 从 v1 升级到 v2（长度前缀编码），`checksum_version` 按行存储以兼容旧行；v2 修复了字段边界碰撞。**注意**：`checksum_version` 本身不在哈希字段里，能改库的写入者可以把 v2 标成 v1 绕过；防篡改靠链，不靠这个字段。【[manager.py:1506-1610](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/provenance/manager.py#L1506-L1610)】

**已确认的接线缺陷（高影响）**：

`DecisionRecorder._track_decision_provenance()`：

```python
self.provenance_manager.track_entity(
    entity_id=decision.decision_id,
    entity_type="decision",
    activity_id="decision_making_process",
    agent_id=decision.decision_maker,
    source_documents=source_documents,   # 没有传 source
    confidence=decision.confidence,
)
self.provenance_manager.track_activity(  # 这个方法不存在
    activity_id=f"decision_{decision.decision_id}",
    ...
)
```

- `ProvenanceManager.track_entity()` 的签名是 `track_entity(entity_id, source, metadata=None, ...)`，`source` 是**必填位置参数**；调用没有传，Python 直接 `TypeError`。
- `ProvenanceManager` 没有 `track_activity` 方法（全仓 `grep "def track_activity"` 无结果）；即使第一行修好，第二行也会 `AttributeError`。
- 两处都在 `try/except` 里，异常被 `logger.exception` 吞掉，决策仍会正常返回；所以调用方**看不到失败**。
- 测试 `tests/context/test_decision_recorder.py` 用 `Mock()` 并手动 `mock_manager.track_activity = Mock()`，把不存在的真实 API 补上了，因此测试通过但没有验证真实 `ProvenanceManager` 契约。

来源：`decision_recorder.py:709-737`；`provenance/manager.py:291-300`；`tests/context/test_decision_recorder.py:36-40`、`:405-416`。

> 上述两条是静态复核（签名/方法存在性），不是运行时结果；建议按 §12.2 的 P0 项在 venv 中确认。

> **对 heirloom 的直接结论**：不要把“溯源”做成一个可选的旁路 manager。写入决策/事实时必须在同一事务里写 provenance 记录，或者用 outbox/event log 保证最终一致；测试必须用真实存储契约，不能用 mock 掩盖缺失方法。

### 5.3 双时态

Semantica 的“bi-temporal”有两个层次：

#### A. `BiTemporalFact`（KG 关系包装器）

| 字段 | 含义 | 默认/行为 |
|---|---|---|
| `valid_from` | 事实在现实世界中开始为真的时间 | 从关系 dict 解析 |
| `valid_until` | 事实在现实世界中不再为真的时间 | `None` → `TemporalBound.OPEN` |
| `recorded_at` | 系统记录该事实的时间（transaction time） | 若缺失，**默认取 `valid_from`**，而不是 ingestion now |
| `superseded_at` | 该记录被后续版本取代的时间 | 默认 `TemporalBound.OPEN` |

`BiTemporalFact.from_relationship()` 是唯一入口；`to_relationship_fields()` 序列化回 dict。源码明确说它只是 backward-compatible wrapper，事实仍以普通关系 dict 存储。【[temporal_model.py:28-65](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/kg/temporal_model.py#L28-L65)】

#### B. `TemporalGraphQuery`（查询层）

`query_at_time` / `query_time_range` / `reconstruct_at_time` 都接受 `time_axis`：

| `time_axis` | 使用的字段 | 含义 |
|---|---|---|
| `"valid"`（默认） | `valid_from` / `valid_until` | 事实在现实中何时为真 |
| `"transaction"` | `recorded_at` / `superseded_at` | 系统何时记录/取代 |
| `"both"` | 两者同时满足 | 两个轴都 active |

`_get_axis_bounds()` 从 `BiTemporalFact.from_relationship()` 取轴；`_relationship_active_at_time` 对 `both` 用 `all(...)`。来源：`temporal_query.py:752-820`。

**缺陷**：

1. **`ContextGraph` 没有 transaction 轴。** `ContextNode`/`ContextEdge` 只有 `valid_from`/`valid_until`；`state_at()` 只按 valid time 过滤。`ContextGraph` 不能回答“在 2024-01-01 时我们**当时以为**的事实是什么”，只能回答“事后看 2024-01-01 有效的事实是什么”。
2. **`recorded_at` 默认值语义错误。** 缺省时用 `valid_from`，会把“事实有效开始时间”当“系统记录时间”。对于回溯导入（backfill），transaction time 会完全失真；文档却说 `recorded_at` 是“auto-stamped at ingestion time”。【[temporal_model.py:40-60](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/kg/temporal_model.py#L40-L60)】【[docs/reference/temporal.md:160-180](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/docs/reference/temporal.md#L160-L180)】
3. **官方适配器让 transaction 字段对查询不可见。** `ContextGraph.to_kg_dict()` 把边 metadata 放在 `rel["metadata"]`，而 `TemporalGraphQuery` 读顶层 `recorded_at`/`superseded_at`；因此 `to_kg_dict()` → `TemporalGraphQuery` 的 transaction 轴会把 `recorded_at` 退化成 `valid_from`。【[context_graph.py:3619-3709](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/context/context_graph.py#L3619-L3709)】
4. **没有“事务时间快照”存储。** `state_at()` 是实时计算；`TemporalVersionManager` 的快照是显式创建的整图副本，不是每个写入自动产生的 transaction-time 版本。

### 5.4 Allen 区间代数

`TemporalReasoningEngine.relation(a, b)` 的判定顺序（简化）：

```python
if a_end < b.start: BEFORE
if a.start > b_end: AFTER
if a_end == b.start: MEETS
if a.start == b_end: MET_BY
if a.start == b.start and a_end == b_end: EQUALS
if a.start == b.start and a_end < b_end: STARTS
if a.start == b.start and a_end > b_end: STARTED_BY
if a_end == b_end and a.start > b.start: FINISHES
if a_end == b_end and a.start < b.start: FINISHED_BY
if a.start < b.start and a_end > b.start and a_end < b_end: OVERLAPS
if a.start > b.start and a.start < b_end and a_end > b_end: OVERLAPPED_BY
if a.start > b.start and a_end < b_end: DURING
return CONTAINS
```

来源：`temporal_reasoning.py:48-92`。

**开放区间缺陷**：`TemporalBound.OPEN` 被 `_end_value()` 转成 `datetime.max`。如果 `a.start=2020-01-01`、`b.start=2021-01-01`，两者都 `OPEN`，则 `a_end == b_end == datetime.max`，命中 `FINISHED_BY` 分支，返回 `finished_by`；但 `contains(a,b)` 返回 `True`。也就是说，`relation()` 对开放区间不是 Allen 代数的正确分类，`CONTAINS/DURING` 会被 `FINISHES/FINISHED_BY` 抢先。

**隔离复现结果**（把两个文件复制到临时目录、替换相对导入后运行）：

```text
open a vs open b: finished_by
contains(a,b): True
closed c vs closed d: finished_by
active_at at end: False
```

**边界不一致**：

- `TemporalReasoningEngine.active_at()`：`start <= point and (end is OPEN or point < end)` → **半开区间** `[start, end)`。
- `ContextNode.is_active()` / `ContextEdge.is_active()`：`if end is not None and now > end: return False` → **闭区间** `[start, end]`。
- 同一个 `valid_until="2024-01-01T00:00:00"`，在 `TemporalReasoningEngine` 里 00:00:00 已不 active，在 `ContextNode.is_active` 里仍然 active。

来源：`temporal_reasoning.py:95-120`、`context_graph.py:431-449`、`:501-518`。

**其他语义**：

- `overlaps()` 把 `MEETS/MET_BY` 视为不重叠；这是“有正持续时间的交集”语义，不是 Allen 的 `OVERLAPS` 关系。命名容易误解。
- `merge_intervals()` 会把 `MEETS` 也合并（`_touches_or_overlaps` 用 `right.start <= left_end`）；对于“相邻但不重叠”的区间，业务上是否该合并需要明确。
- `normalize_interval()` 会把结束时间按粒度扩到该粒度的最后一刻（如 day → 23:59:59.999999），与 `active_at` 的半开语义组合时要注意。
- `retroactive_coverage()` 用 `contains` / `overlaps` 把原始事实分成 affected/partial/unaffected；它不处理 transaction time。

### 5.5 快照

Semantica 有四种“快照”概念，用途不同：

| 机制 | 输入 | 输出 | 是否持久化 | 用途 |
|---|---|---|---|---|
| `ContextGraph.state_at(timestamp)` | 时间戳 | `{timestamp, nodes, edges, entities, relationships, decisions}` | 否（实时计算） | 点时间图视图 |
| `TemporalGraphQuery.reconstruct_at_time(graph, at_time, time_axis=...)` | 图 dict | 深拷贝子图 | 否 | 在 KG dict 上按 valid/transaction 轴重建 |
| `TemporalVersionManager.create_snapshot(graph, label, author, description)` | 图 dict | `{format_version, label, timestamp, author, description, entities, relationships, metadata, checksum}` | 是（InMemory/SQLite） | 版本化全图快照、diff、revision |
| `TruthSnapshotProvider` | session/bitemporal view | 不可变 read view | 否（内存） | grounded context 的一致性读取 |

**`ContextGraph.state_at()`**：

- 过滤 `node.is_active(at_time)` 的节点，以及两端都 active 的边；
- 决策 payload 只包含 `category/scenario/reasoning/outcome/confidence/timestamp/decision_maker/entities/valid_from/valid_until/metadata`；
- 不做 transaction-time 过滤，不返回 provenance；
- `timestamp` 归一化为 tz-naive UTC 比较。

来源：`context_graph.py:3760-3803`。

**`TemporalVersionManager.create_snapshot()`**：

- 校验 `ChangeLogEntry`（author/description 必填）；
- 复制 `entities` / `relationships`（关系经 `relationship_to_json_ready`）；
- 计算 SHA-256 checksum；
- 存到 `VersionStorage`（默认内存；可 SQLite）；
- `apply_revision()` 不删除原始事实，而是 supersede 匹配版本；重叠的 retroactive revision 会警告并保留所有版本。

来源：`temporal_query.py:1336-1500`。

**问题**：

- `ContextGraph.state_at()` 不是“存储的快照”，每次调用都重新过滤全图；大图上 O(N+E)。
- `TemporalVersionManager` 快照是**全量复制**，不是增量/delta；`pipeline` 的 `delta_mode` 是另一套机制，两者没有统一。
- `state_at()` 返回的 decisions 里 `timestamp` 可能是 float epoch（`record_decision`）或 ISO 字符串（`add_decision(Decision)`），消费者需要自己兼容。
- `TruthSnapshotProvider` 是较新的 grounded context 机制，和 `TemporalVersionManager` 不是同一套；文档分别描述，容易让实现者以为有统一 snapshot 层。

### 5.6 时序一致性校验

`TemporalGraphQuery.validate_temporal_consistency(graph)` 检查：

- 反向区间（`valid_from > valid_until`）；
- 同一 source/target/type 的重叠事实；
- 实体生命周期违规（关系窗口超出实体窗口）。

返回 `TemporalConsistencyReport`。来源：`temporal_query.py:215-362`。

**限制**：

- 只对传入的 `graph` dict 做检查，不检查 `ContextGraph` 内部的 `_decisions` / `_retractions` / `_tombstones`；
- 不检查 transaction time 与 valid time 的交叉一致性（例如 `recorded_at` 晚于 `valid_until` 是否合理）；
- 不检查 Allen 关系是否自洽（因为 `relation()` 本身对开放区间有缺陷）。

---

## 6. 推理能力与已知边界

### 6.1 能力矩阵

| 引擎 | 输入 | 输出 | 确定性 | 主要限制 |
|---|---|---|---|---|
| `Reasoner.forward_chain()` | 事实字符串/图 dict + `IF ... AND ... THEN ...` 规则 | `InferenceResult`（conclusion, rule, premises, confidence） | 是 | 只支持 AND；无 OR/否定/比较/算术；最多 50 轮 |
| `Reasoner.backward_chain()` | 目标事实 + 规则 | 证明/结果 | 是 | 同样受规则语法限制 |
| `ReteEngine` | `Rule` 列表 + `Fact` | `Match` / `InferenceResult` | 是 | 正则字符串匹配；每规则独立 alpha；无事实撤回；无否定/聚合 |
| `DatalogReasoner` | `predicate(args)` 事实/规则 | 派生事实字符串/查询 bindings | 是 | 无否定/聚合/比较；常量小写化；字符串语法限制 |
| `SPARQLReasoner` | 三元组 + SPARQL | `SPARQLQueryResult` | 是（取决于后端） | 必须有 triplet store；Explorer 只读且有上限；缓存不失效 |
| `TemporalReasoningEngine` | `TemporalInterval` | `IntervalRelation` / bool / intervals | 是 | 开放区间 `relation()` 有缺陷；边界语义不一致 |
| `TruthMaintenanceSession` | 固定非递归规则 + supports/retractions | `MaintenanceDelta` | 是 | 非递归、布尔支持、无副作用、无存储、无线程安全、无时序 |
| `GraphReasoner` | 图 + LLM | 自然语言/推断 | 否（LLM） | 依赖 provider；不在“确定性”范围 |
| `ExplanationGenerator` | 推理结果 | 解释/步骤 | 是 | 解释的是已有结果，不是独立推理 |

### 6.2 Forward/backward chaining

`Reasoner._parse_rule_definition()` 只识别：

```text
IF <conditions joined by AND> THEN <conclusion>
```

- `conditions` 按 `\s+AND\s+` 切分；
- 变量是 `?x` 形式；`Fact` 是 `Predicate(arg1, arg2)` 字符串；
- `forward_chain()` 反复应用所有规则直到 fixpoint 或 `max_iterations=50`；
- 同一 pass 内新结论立即可被后续规则使用；
- 动作（`AssertAction` / `RetractAction` / `CallAction` / `EmitEventAction`）按 activation 只触发一次；
- 结论去重，多个 derivation 会合并 premises；
- 置信度来自规则 `confidence`（不是证据概率）。

来源：`reasoner.py:579-680`、`:751-797`；`_rule_matching.py`。

**限制**：

- 没有 OR、否定、比较、算术、聚合、函数符号、嵌套 term；
- 变量只能出现在 `?name`，不能有类型约束；
- 规则匹配是字符串/正则级，不是类型化统一；
- `max_iterations=50` 可能截断需要更多轮的递归；没有“达到上限”的显式错误，只是停止。

### 6.3 Rete

`ReteEngine` 的结构：

- `AlphaNode(condition)`：单条件匹配；`_matches` 用预编译正则匹配 `str(fact)`；有 arity gate。
- `BetaNode(left, right)`：join 两个 token。
- `TerminalNode(rule)`：激活规则。
- `build_network(rules)`：**为每条规则的每个条件新建 alpha 节点**，再串成 beta 链，最后接 terminal；没有跨规则共享 alpha 节点。
- `add_fact(fact)`：把 fact 追加到 `facts`，然后遍历**整个 network** 的所有 alpha 节点，逐个尝试匹配；匹配的 token 再沿 children 传播。
- `execute_matches()`：实例化结论并返回；有 activation history 防止动作重复触发。
- **没有 `remove_fact` / `retract_fact`**；事实只能增加，撤回要靠重建网络或 `TruthMaintenanceSession`（另一套）。

来源：`rete_engine.py:359-500`、`:552-700`。

**与文档的差距**：

- 文档说 “Rete 100+ rule sets / avoids re-evaluating unchanged conditions”；实现确实有 alpha/beta/terminal 结构和增量 fact 传播，但**每条规则独立建 alpha 节点**、每个新 fact 遍历全网络、匹配靠正则字符串，不是经典 Rete 的共享 alpha memory / beta memory 索引。规则数增长时，节点数和每 fact 的匹配次数线性增长；没有基准证明 100+ 规则的性能。
- 没有否定节点、没有聚合、没有事实撤回、没有 truth maintenance 集成。`TruthMaintenanceSession` 是独立的、非递归的会话层，明确说“existing Reasoner, actions, RETE, and Datalog behavior is unchanged”。【[truth_maintenance.md:1-20](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/docs/reference/truth_maintenance.md#L1-L20)】

### 6.4 Datalog

`DatalogReasoner`：

- 事实：`DatalogFact(predicate, args)`；规则：`DatalogRule(head_predicate, head_args, body)`；
- 语法：`head(X, Y) :- body1(X, Z), body2(Z, Y).`；
- 半朴素（semi-naive）自底向上 fixpoint：`_delta_old` / `_delta_new`，每轮只对 delta 求值；
- 支持递归（如 `ancestor`）；
- 查询：`query("ancestor(tom, ?Y)")`，返回 bindings；
- 图加载：`load_from_graph(graph)` 把节点/边转成事实。

来源：`datalog_reasoner.py:19-39`、`:242-430`。

**限制**：

- **无常量类型**：所有参数都是字符串；没有数字/日期比较。
- **无否定、无聚合、无比较、无内置谓词**。
- **常量小写化**：`add_fact` 对 dict 事实的 subject/object/predicate 做 `.lower()`；`load_from_graph` 走 `add_fact`，所以 `APT29` → `apt29`，`ThreatActor` → `threatactor`。字符串事实语法 `_parse_fact_string` 拒绝以大写字母开头的参数（把它当变量），因此无法在字符串事实里保留大写常量。文档明确写了“All predicates and arguments are lowercased”。【[datalog_reasoner.py:70-120](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/reasoning/datalog_reasoner.py#L70-L120)】【[docs/guides/reasoning.md:63-70](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/docs/guides/reasoning.md#L63-L70)】
- 没有 arity 声明/校验（除运行时 unification 失败）。
- 终止性靠有限事实集；没有 magic set/query optimization。

### 6.5 SPARQL

`SPARQLReasoner`：

- 构造时必须传 `triplet_store`；没有 triplet store 直接 `ProcessingError`，不会返回空结果（避免“无匹配”误读）。【[sparql_reasoner.py:369-390](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/reasoning/sparql_reasoner.py#L369-L390)】
- 执行路径：
  1. 若 store 有 `execute_query` 且后端支持 `execute_sparql`，走原生；
  2. 若 inference 规则需要物化，或后端不支持 SPARQL，则 `get_triplets()` 拉全量到 rdflib `Graph`，在内存执行；
  3. 结果可再跑 `infer_results()` 做规则后处理。
- 查询缓存：按 `(query, options)` 缓存，**没有失效机制**；修改 store/规则后必须手动 `clear_cache()`。
- Explorer 的 `/api/sparql` 是另一条路径：它把当前 `GraphSession` 投影成 rdflib 图，URI 是 `http://semantica.local/entity/` / `http://semantica.local/prop/`，只允许 SELECT/ASK/CONSTRUCT/DESCRIBE，禁止 UPDATE 关键字，节点/边上限 50,000，行上限 5,000，超时 30s。【[explorer/routes/sparql.py:120-190](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/explorer/routes/sparql.py#L120-L190)】【[explorer/routes/sparql.py:209-300](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/explorer/routes/sparql.py#L209-L300)】

**限制**：

- `ContextGraph` 本身不能执行 SPARQL；必须走 triplet store 或 Explorer 的临时投影。
- Explorer 投影的 IRI 与 `RDFExporter` 的 `https://semantica.dev/ns#` 不一致；同一个图在 SPARQL 和导出文件中不是同一套 IRI。
- Explorer 投影不包含 provenance、决策专用字段、嵌套 metadata 的完整语义；它把 node properties 全部变成字面量，edge 只变成 `prop:<type>` 三元组。
- 缓存无失效是审计场景的风险：同一查询在数据变更后仍返回旧结果。

### 6.6 SHACL / OWL / SKOS

**SHACL**：

- `OntologyValidator` / `OntologyEngine.validate_graph()` 调用 `pyshacl.validate(data_g, shacl_graph=shacl_g, inference="none", abort_on_first=False)`。
- `pyshacl` 是 optional extra `[shacl]`（`pyproject.toml:129-130`）；没有安装时抛 `ImportError`，提示 `pip install semantica[shacl]`。
- `SHACLGenerator` 从 ontology dict 生成 NodeShape/PropertyShape；支持 Turtle/JSON-LD/N-Triples；`quality_tier="strict"` 会设置 `closed=True`。
- 生成的 shapes 覆盖 `minCount/maxCount/datatype/class` 等基础约束；`qualifiedMinCount/qualifiedMaxCount` 在解释里没有完整处理（源码注释明确）。

来源：`ontology_validator.py:276-397`；`ontology_generator.py:793-900`；`pyproject.toml:129-130`。

**OWL**：

- `OWLGenerator` 用 rdflib 生成基础 OWL 公理；`OWLExporter` 支持 Turtle/RDFXML/JSON-LD 等。
- **没有 OWL DL 推理**：`OntologyValidator(reasoner="hermit")` 是占位；源码注释 “Placeholder implementation for now / In a real implementation, this would load owlready2 or similar”；`check_consistency` / `check_satisfiability` 是 `pass`。
- 文档内部也不一致：`docs/guides/ontology.md:26` 说 “Semantica exports the ontology; reasoning itself runs in the external tool”，但 `semantica/ontology/methods.py:53` 和 `ontology_usage.md:96` 声称内置 HermiT/Pellet 集成。以源码为准：**没有内置 DL 推理**。【[docs/guides/ontology.md:26](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/docs/guides/ontology.md#L26)】【[methods.py:53](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/ontology/methods.py#L53)】【[ontology_validator.py:326-370](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/ontology/ontology_validator.py#L326-L370)】

**SKOS**：

- `ContextGraph.add_edge()` 对 `skos:broader` / `skos:narrower` 做环检测；`validate_skos_hierarchy()` 把两种方向归一化为 child→parent，检测新边是否引入环；只检查受影响的 concept，不阻止预先存在的无关环。
- `OntologyEngine` 有 SKOS vocabulary CRUD/search：`list_skos_schemes`、`list_skos_concepts`、`create_skos_concept` 等，通过 triplet store 的 SPARQL 实现。
- 没有完整 SKOS 完整性条件（如 `skos:related` 与 `broaderTransitive` 的 disjointness、label 唯一性等）；只有层级环检测。

来源：`utils/skos.py:1-100`；`ontology/engine.py:404-520`。

### 6.7 Truth maintenance

`TruthMaintenanceSession` 是 Semantica 较新的、设计得比较诚实的能力：

- 维护 `session.facts == closure(fixed_rules, facts_with_remaining_external_support)`；
- supports 是外部事实断言，带 `support_id`；derivations 记录规则、premises、bindings、conclusion；
- `apply(assertions, retractions)` 批量更新，返回净 `MaintenanceDelta`；
- 只删除失去所有支持的结论；有替代推导的结论保留；
- `explain(fact)` 返回当前状态的 `FactExplanation`；
- `to_checkpoint` / `from_checkpoint` 显式序列化。

**官方限制**（docs/reference/truth_maintenance.md:244-254）：

- 非递归规则；构造时拒绝循环谓词依赖；
- 窄原子语法：ASCII predicate/variable、无引号标量常量、零元；不支持 quoted strings、whitespace constants、nested terms、comparisons、aggregation、negation、disjunction；每个 predicate 固定 arity；
- 布尔支持语义，无置信度传播/概率；
- 无副作用（不执行 actions/handlers）；
- 无自动存储/恢复；checkpoint 只是序列化格式；
- 无线程安全/多写者；
- 无时序有效性/跨存储同步；
- 固定规则，规则变更需新建 session；
- 无历史证明归档，`explain()` 只描述当前状态。

> 这套限制写得很好，适合 heirloom 直接借鉴为“v0.1 真值维护的边界声明”。Semantica 没有把 TMS 集成进 Rete/Datalog，heirloom 若要在 Rust 核心里做增量撤回，需要从一开始就把“支持/推导”作为一等结构。

### 6.8 推理层已知缺陷汇总

| 问题 | 证据 | 影响 |
|---|---|---|
| Forward chain 无 OR/否定/比较 | `reasoner.py:751-797`；规则按 `AND` 切分 | 复杂规则要拆成多条，或换引擎 |
| Rete 无共享 alpha、无撤回、正则匹配 | `rete_engine.py:405-500`；无 `remove_fact` | 规则数/事实数大时性能不成立；“100+ rules” 无基准 |
| Datalog 常量小写化 | `datalog_reasoner.py:70-120`；文档 `reasoning.md:63-70` | 标识符大小写敏感的系统会丢信息/碰撞 |
| Datalog 无否定/聚合/比较 | 规则体只接受 predicate atoms | 不能表达“没有某属性”“计数 > N”等 |
| SPARQL 依赖 triplet store | `sparql_reasoner.py:369-390` | ContextGraph 不能直接 SPARQL |
| Explorer SPARQL IRI 与导出不一致 | `sparql.py:120-121` vs `rdf_exporter.py:51` | 同一图在查询/导出中不可互操作 |
| SPARQL 缓存无失效 | `sparql_reasoner.py:369-430` | 审计查询可能返回旧结果 |
| OWL DL 验证占位 | `ontology_validator.py:326-370` | 不能声称 OWL 2 DL 一致性检查 |
| SHACL `inference="none"` | `ontology_validator.py:194` | 依赖 subclass/domain 推理的约束不会自动生效 |
| TMS 非递归、无时序 | `truth_maintenance.md:244-254` | 不能直接做带时间的撤回 |

---

## 7. 对外表面：Python / CLI / MCP / REST / Explorer

### 7.1 Python 模块与关键类

`semantica` 的 Python 表面是“按包导入”的模块集合，不是单一 facade。常用入口：

| 入口 | 关键类/函数 |
|---|---|
| `semantica.context` | `ContextGraph`, `AgentContext`, `AgentMemory`, `Decision`, `Policy`, `PolicyEngine`, `DecisionRecorder`, `DecisionQuery`, `CausalChainAnalyzer`, `ContextRetriever`, `TemporalGraphRetriever`, `GroundedContextAssembler` |
| `semantica.kg` | `GraphBuilder`, `KnowledgeGraph`, `TemporalGraphQuery`, `TemporalVersionManager`, `BiTemporalFact`, `TemporalBound`, `CentralityCalculator`, `CommunityDetector`, `PathFinder`, `LinkPredictor` |
| `semantica.reasoning` | `Reasoner`, `Rule`, `Fact`, `ReteEngine`, `DatalogReasoner`, `SPARQLReasoner`, `TemporalReasoningEngine`, `IntervalRelation`, `TruthMaintenanceSession`, `ExplanationGenerator` |
| `semantica.provenance` | `ProvenanceManager`, `ProvenanceEntry`, `verify_chain`, `compute_checksum` |
| `semantica.ontology` | `OntologyEngine`, `OntologyGenerator`, `SHACLGenerator`, `OWLGenerator`, `OntologyValidator`, `SHACLValidationReport` |
| `semantica.pipeline` | `PipelineBuilder`, `Pipeline`, `PipelineStep`, `ExecutionEngine`, `ExecutionResult` |
| `semantica.export` | `RDFExporter`, `JSONExporter`, `OWLExporter`, `CSVExporter`, `ParquetExporter` |
| `semantica.core` | `Semantica`, `ConfigManager`, `PluginRegistry`, `MethodRegistry`, `build_knowledge_base`, `run_pipeline` |

公开导出以各包 `__init__.py` 的 `__all__` 为准；例如 `semantica.context.__all__` 有 60+ 名称，`semantica.reasoning.__all__` 有 40+。这说明 API 面很宽，但稳定性/版本化没有统一契约。

### 7.2 CLI

`semantica/cli.py` 用 Click 实现，约 7000 行，包含：

- 顶层命令：`info`、`shell`、`changelog`、`doctor`、`init`、`watch`、`ingest`、`parse`、`split`、`normalize`、`extract`、`deduplicate`、`export`、`completion`，以及 hidden `build`。
- 命令组（括号内为子命令）：
  - `kg`（`build`, `query`, `stats`, `analyze`, `find-path`, `resolve`, `predict`, `validate`, `global`, `drift`）
  - `pipeline`（`init`, `validate`, `run`, `status`, `stop`）
  - `embed`（`generate`, `search`, `index`）
  - `reason`（`run`, `explain`, `query`, `list`）
  - `decision`（`record`, `list`, `query`, `trace`, `similar`, `impact`, `check`）
  - `temporal`（`snapshot`, `query`, `history`, `distance`, `allen`）
  - `provenance`（`lineage`, `audit`, `export`, `check`, `invalidate`, `verify-chain`, `descendants`）
  - `validate`（`shacl`, `conflicts`, `integrity`）
  - `ontology`（`generate`, `import`, `validate`, `shacl`, `align`, `health`, `version`；`ontology skos` 子组 2 个）
  - `visualize`、`store`（`list/connect/stats/migrate/flush`）、`backup`（`info/create/sync/restore/schedule`）
  - `server`（`start/stop/status`）、`explorer`（`start/stop/status/open`）、`mcp`（`start/stop/status/list-tools/call`）

按装饰器统计约 91 个 leaf command（含 hidden）；官方 `ARCHITECTURE.md:67` 说 “CLI 50+ commands”，这个数字是保守的，不是矛盾。来源：`semantica/cli.py:602-6925`。

**CLI 的注意点**：

- `decision` / `temporal` / `provenance` 命令是“独立薄封装”，不是通过统一 service 层；不同命令可能走文件、图后端或内存路径。
- `semantica server start`、`semantica explorer start`、`semantica mcp start` 是三个不同的进程入口；没有统一的 `serve` facade。
- `semantica doctor` 是健康检查；`SEMANTICA_DISABLE_PROGRESS` / `SEMANTICA_FORCE_PROGRESS` 控制进度条（README:157-165）。

### 7.3 MCP

**架构**：`semantica_mcp` 是独立包，stdio JSON-RPC；不是 HTTP/SSE。客户端启动 `semantica-mcp` 子进程，通过 stdin/stdout 通信。图从 `SEMANTICA_KG_PATH` 加载；`SEMANTICA_KG_AUTO_RELOAD=true` 时每次操作前检查文件 revision。没有内建认证（本地子进程权限模型）。

**真实工具（22 个）**，按模块：

| 组 | 工具 |
|---|---|
| Extraction（3） | `extract_entities`, `extract_relations`, `extract_all` |
| Decision（6） | `record_decision`, `query_decisions`, `find_precedents`, `get_causal_chain`, `analyze_decision_impact`, `link_decisions` |
| Graph（5） | `add_entity`, `add_relationship`, `search_graph`, `get_graph_summary`, `get_graph_analytics` |
| Reasoning（2） | `run_reasoning`, `abductive_reasoning` |
| Export（2） | `export_graph`, `get_provenance` |
| Retrieval（4） | `store_document`, `retrieve_context`, `update_document`, `remove_document` |

来源：`semantica_mcp/mcp/tools/*.py` 的 `TOOL_DEFINITIONS`；`tests/test_mcp_tool_registry_consistency.py:44-48` 断言 22。

**Resources（3）**：`semantica://graph/summary`、`semantica://decisions/list`（最多 50 条）、`semantica://schema/info`。来源：`docs/reference/mcp_server.md:525-533`。

**文档漂移**：

- 官方文档写 “15 tools”，并列出 `query_graph` / `update_node` / `delete_node`；真实工具表没有这三个，实际是 `search_graph`，且没有 update/delete 节点工具。
- 文档 `find_precedents` 写 “Hybrid similarity search”；实际 `ContextGraph` 路径是 lexical + 结构先验（见 §4.4）。
- `add_entity` 的实现：`graph.add_node(node_id=node_id, label=args.get("label"), node_type=args.get("type"), metadata=args.get("metadata", {}))`。`ContextGraph.add_node(node_id, node_type, content=None, **properties)` 的 `content` 没传，所以 `content = node_id`；`label` 和 `metadata` 都落入 `**properties`，`metadata` 变成嵌套 key。结果是：节点显示名不是 label，metadata 不是展开属性。除非后续有代码专门读取 `properties["label"]` / `properties["metadata"]`，否则 Explorer/搜索会看到 id 而不是 label。

来源：`semantica_mcp/mcp/tools/graph.py:18-35`；`context_graph.py:1141-1173`；`docs/reference/mcp_server.md:172-190`。

### 7.4 REST

**服务入口**：`python -m semantica.server` 启动 FastAPI；`semantica.explorer` 也可独立启动 `semantica-explorer --graph <json>`。REST 路由由 Explorer 的 13 个 router 挂载；没有单独的 “core REST API” 包。

**基础端点**（`semantica/server.py`）：

| 方法/路径 | 作用 |
|---|---|
| `GET /health` | 健康检查 |
| `GET /api/info` | 名称、版本、capabilities |
| `POST /build` | 从 sources 构建 knowledge base |
| `GET /{full_path:path}` | SPA catch-all（返回前端） |

**Explorer router**（前缀 / 路由数 / 代表端点）：

| 前缀 | 路由数 | 代表端点 |
|---|---:|---|
| `/api/graph` | 12 | `GET /nodes`, `GET /node/{id}`, `GET /node/{id}/neighbors`, `GET /edges`, `GET /path`, `POST /search`, `POST /distance-matrix`, `GET /semantic-neighborhood`, `GET /stats` |
| `/api/analytics` | 2 | `GET ""`, `GET /validation` |
| `/api/decisions` | 6 | `GET ""`, `GET /causal-distance`, `GET /{id}`, `GET /{id}/chain`, `GET /{id}/precedents`, `GET /{id}/compliance` |
| `/api/temporal` | 5 | `GET /snapshot`, `GET /diff`, `GET /patterns`, `GET /bounds`, `GET /distance-history` |
| `/api/enrich`（无前缀） | 5 | `POST /api/enrich/extract`, `/api/enrich/links`, `/api/enrich/dedup`, `/api/reason`, `/api/enrich/merge` |
| `/api`（export/import） | 3 | `POST /api/import`, `/api/export`, `/api/export/distance-enriched` |
| `/api/markdown` | 2 | `GET` / `PUT` 节点 Markdown |
| `/api/memories` | 1 | `GET ""` |
| `/api/annotations` | 3 | `GET/POST/DELETE` |
| `/api/sparql` | 1 | `POST ""` |
| `/api/provenance` | 3 | `GET ""`, `GET /report` |
| `/api/vocabulary` | 4 | `GET /schemes`, `/concepts`, `/hierarchy`, `POST /import` |
| `/api/ontology` | 36 | registry/preview/load/create/search/graph/entity/SKOS/alignments/health/SHACL/versions/proposals 等 |

总计 **83 个 `@router` 处理器**（不含 `server.py` 的 3 个 + SPA）。`ARCHITECTURE.md:67` 写 “REST API 100+ endpoints”，与实际计数不符；如果按 FastAPI 生成的 operation 计，可能接近/超过 100，但仓库内没有该计数。

**认证/CORS**：

- `SEMANTICA_API_KEY` 设置后，受保护路由要求 API key；未配置时，受保护路由返回 503（日志警告）。
- `SEMANTICA_ALLOW_ANONYMOUS=true` 可放开所有 Explorer 路由（仅限本地）。
- `ALLOWED_ORIGINS`（或 `EXPLORER_CORS_ORIGINS`）控制 CORS 和 WebSocket origins。
- `semantica-explorer` CLI 只有 `--graph/-g`、`--port/-p`、`--host`、`--no-browser` 四个 flag；没有 auth/CORS flag。
- WebSocket：`/ws/graph-updates`，推送 graph mutation 事件。

来源：`semantica/server.py:167-247`；`semantica/explorer/app.py:181-197`；`docs/reference/explorer.md:95-130`。

### 7.5 Explorer 信息架构

Explorer 是一个 React 19 + Sigma.js 前端 + FastAPI 后端；前端代码在 `explorer/src/`。它的导航不是“单一图页面”，而是六个 workspace：

| 顶层 workspace | 子视图/内容 |
|---|---|
| **Knowledge Explorer** | Graph / Memories / Vocabulary 三个 tab；Graph 是主画布 |
| **Analyze** | `sparql` / `reasoning` |
| **Decisions** | 决策列表 + 因果链流程 |
| **Enrich** | `import` / `merge` / `registry` / `resolve` |
| **Manage** | `lineage` / `kg-overview` / `ontology` |
| **Ontology Hub** | registry、search、graph、SKOS、SHACL Studio、alignments、health、versions、proposals |

来源：`explorer/src/App.tsx:84-91`；`explorer/src/ExploreWorkspaceTabs.tsx`；`explorer/src/workspaces/*`。

**Graph workspace 的交互**：

- 画布：Sigma.js / ForceAtlas2；节点/边选择、inspector 面板、Markdown 内容查看/编辑；
- 搜索：`POST /api/graph/search`，带 suggestion list；
- 路径：`GET /api/graph/path?source=&target=`；双向路径；
- 邻居展开：`GET /api/graph/node/{id}/neighbors?depth=2`；
- Ego mode：BFS 深度 1–8，按距离淡化；
- Distance heatmap：按 hop 数/距离着色；
- Timeline：底部时间轴，playhead 可播放/拖动，驱动图的时间过滤；
- 语义邻域：`GET /api/graph/semantic-neighborhood?node_id=&top_k=20`；
- 距离矩阵：`POST /api/graph/distance-matrix`；
- 标注：`/api/annotations`；
- 实时：WebSocket `/ws/graph-updates`。

来源：`explorer/src/workspaces/GraphWorkspace/GraphWorkspace.tsx:1267-1312`、`:1790-1810`；`TimelinePanel.tsx:60-180`；`docs/reference/explorer.md:133-190`。

**Decisions workspace**：左侧决策列表（`GET /api/decisions`），选中后 `GET /api/decisions/{id}/chain`，用 `CausalFlow` 渲染节点/边；没有 precedent/impact/compliance 的 UI tab（这些只有 REST 端点）。来源：`DecisionWorkspace.tsx:95-150`。

**Ontology Hub**：registry、search、graph、entity detail、SKOS schemes/concepts/hierarchy、alignments（获取/创建/删除/建议）、health、SHACL generate/shapes/validate、versions/compare、proposals/approve/reject/publish/comment。对应 `/api/ontology/*` 36 个路由。来源：`semantica/explorer/routes/ontology.py`；`docs/reference/explorer.md:150-190`。

**Explorer 的限制**（官方文档承认）：

- CLI 把整个 JSON 文件加载进内存；>10k 节点时力导向布局不可用，建议先过滤子图。
- SPARQL 限制 50k 节点/边、5k 行、30s。
- 没有多租户；单 `GraphSession`；API key 是单密钥。
- 图搜索索引是进程内内存索引；重启后重建。

来源：`docs/reference/explorer.md:139-147`、`:379-381`；`explorer/routes/sparql.py:177-190`。

### 7.6 表面漂移清单

| 断言 | 文档 | 源码/测试 | 影响 |
|---|---|---|---|
| MCP 工具数 | 15（官方站 + repo 文档） | 22（`TOOL_DEFINITIONS`；测试断言 22） | 客户端按 15 个工具做能力发现会漏掉 retrieval/decision impact/link 等 |
| MCP 工具名 | `query_graph`, `update_node`, `delete_node` | 实际 `search_graph`；无 update/delete node 工具 | 文档示例无法直接调用 |
| REST 端点 | 100+ | 83 个 `@router` | 容量/范围评估失真 |
| CLI 命令 | 50+ | ~91 leaf（含 hidden） | 不是矛盾，但说明表面很大、维护成本高 |
| `find_precedents` | “semantic / hybrid” | lexical + 结构先验；flag 无效 | 用户会以为有向量检索 |
| `ContextGraph` | “FAISS vector index for semantic search” | `ContextGraph` 无 vector store/embedding 字段；`AgentContext` 才包 vector store | 误以为图本身有语义索引 |
| `add_entity` | “Add a node” | label/metadata 落入 properties，content=id | 图显示名/metadata 语义与预期不符 |
| `record_decision` | “full provenance” | 无 source 参数；ContextGraph 无 provenance 字段；DecisionRecorder 接线坏 | 合规场景不可直接依赖 |
| `evaluators` | 决策指南说 context_graph backend 不运行 | 源码 `AgentContext.record_decision` 在两个 backend 都运行；CHANGELOG Unreleased 也说 both | 文档滞后，行为以源码为准 |

---

## 8. 性能主张、基准方法与瓶颈

### 8.1 官方性能主张

| 主张 | 数字 | 来源 | 方法/备注 |
|---|---|---|---|
| Node search (118k nodes) | 24 ms → 0.004 ms（6000×） | `README.md:1178-1187`；`docs/architecture.md:186`；`docs/reference/explorer.md:379` | v0.5.0，118k 生产图，AMD EPYC/64GB；dedup/candidate 数字是 CHANGELOG 历史测量，不是测试断言 |
| Embedding cache hit | 10× throughput | `README.md:1184` | revision-based cache；无脚本 |
| Semantic deduplication | 6.98× faster | `README.md:1185` | historical |
| Candidate generation | 63.6% faster | `README.md:1186` | blocking strategy；historical |
| Dedup v2 | up to 7× | `docs/architecture.md:185` | `blocking_v2/hybrid_v2/semantic_v2` |
| Explorer indexed search | 0.004 ms on 118k | `docs/reference/explorer.md:9`、`:139` | 内存倒排索引 + LRU 缓存；未给查询/数据集 |
| BFS path (118k) | < 50 ms | `docs/reference/explorer.md:381` | 无方法细节 |
| SPARQL | 50k nodes / 5k rows / 30s cap | `explorer/routes/sparql.py:177-190` | 这是限制，不是性能主张 |
| Rete | 100+ rules | `docs/guides/reasoning.md:314` | 无基准；实现是每规则独立 alpha + 全网络遍历 |

### 8.2 基准方法评估

**仓库里实际存在的基准**：

1. `semantica/benchmarks/`：一个 memory QA harness，数据集 `sample`（6 题）、`hotpotqa`、`musique`、`locomo`；系统 `lexical`、`semantica`、`mem0`、`graphiti`、`cognee`；指标 `token_f1` / `normalized_exact_match`。README 明确说：“This is phase 1. It is a working harness with adapters, not a leaderboard: no dataset ships with it beyond a tiny hand-written smoke set, and no result table is published yet.”【[benchmarks/README.md:1-12](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/benchmarks/README.md#L1-L12)】
2. `tests/vector_store/test_performance_benchmarks.py`：测 `DecisionContext.record_decision`、批量决策、hybrid search、内存占用；阈值是 100ms/decision、10ms/batch、50ms/search、150ms/context retrieval、1KB/decision；使用 `inmemory` vector store。它**不测** 118k 图搜索，也没有 0.004ms 断言。【[test_performance_benchmarks.py:52-60](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/tests/vector_store/test_performance_benchmarks.py#L52-L60)】
3. Explorer `SearchIndex`：exact/token/prefix 三级索引 + LRU（128 slots）；`upsert` 用 `bisect.insort` 维护有序 id。0.004ms 很可能是**已缓存的精确查询命中**，不是通用图查询/遍历/推理吞吐。【[search_index.py:97-190](https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/explorer/search_index.py#L97-L190)】

**结论**：

- 官方数字缺少数据集、查询集、硬件脚本、重复次数、冷/热缓存区分；不能作为 heirloom 的性能基线。
- `0.004ms` 是单次内存索引查找的量级，不能外推到图遍历、SPARQL、推理、决策写入。
- 没有 Rete/Datalog/SPARQL/Decision 写入的公开基准；`semantica/benchmarks` 是 QA 质量 harness，不是图性能 harness。
- README 自己承认 dedup/candidate 是 historical，不是自动测试；这降低了整张表的可信度。

### 8.3 已知瓶颈/成本

| 瓶颈 | 证据 | 影响 |
|---|---|---|
| 单进程内存图 + RLock | `ContextGraph` 全在 `dict/list`，写路径持锁 | 多写者串行；大图内存占用高；无法跨进程共享 |
| 整图 JSON/Markdown 持久化 | `save_to_file` 写全图 | 写入放大；大图保存慢；无 WAL/增量 |
| Explorer 整文件加载 | `docs/reference/explorer.md:147` 警告 >10k 节点 | 大图无法交互；需要先过滤 |
| 图搜索 O(candidates) | `find_precedents_by_scenario` 遍历候选做 Jaccard | 决策多时 precedent 查询线性增长 |
| 因果链路径枚举 | `trace_decision_causality` 枚举路径，`max_chains=10000` | 密集因果图组合爆炸；截断会丢链 |
| 决策写入放大 | 1 decision + N entity + category + maker + 边 | 每条决策至少 4 节点/3 边；批量写入成本高 |
| Rete 每规则独立 alpha + 全网络遍历 | `_add_rule_to_network` / `_propagate_fact` | 规则/事实增长时线性甚至更差；无共享记忆 |
| Datalog 全量 fixpoint | `derive_all` 反复扫 delta | 递归/大事实集成本高；无 magic set |
| SPARQL 需要全量 triplet 到 rdflib | `_execute_on_rdflib_graph` | 大图内存/延迟；Explorer 直接 50k 上限 |
| AgentMemory keyword fallback | `_keyword_search` 线性扫 `memory_items` | 无 vector store 时 O(N) |
| Python GIL | 纯 Python 实现 | CPU-bound 推理/抽取无法利用多核；Rust 核心的优势点 |

---

## 9. W3C 标准一致性与偏离

### 9.1 总表

| 标准 | Semantica 的实现 | 一致性评估 | 主要偏离/风险 |
|---|---|---|---|
| **RDF 1.1** | 自研 `RDFSerializer` 输出 Turtle/RDFXML/N-Triples；rdflib 是核心依赖但导出路径不走 rdflib 解析 | **部分** | Turtle 语法由字符串拼接；`RDFValidator.validate_rdf_syntax()` 只检查 dict 字段，不解析 RDF；没有 RDF 1.1 全语法/数据类型/语言标签的保证 |
| **RDF/XML** | 自研 `serialize_to_rdfxml` | **部分** | 字符串模板；没有 rdflib 序列化器保证；复杂字面量/命名空间边界需实测 |
| **JSON-LD 1.1** | 自研 `serialize_to_jsonld` + `JSONExporter`；没有 JSON-LD processor | **部分/偏离** | 实体 `@type` 被降级为 `semantica:Entity` + `semantica:type` 字符串，而不是类 IRI；没有 `@vocab`；没有 expansion/compaction；不是 JSON-LD 1.1 处理器 |
| **OWL 2** | `OWLGenerator` 用 rdflib 生成基础 OWL 类/属性、`subClassOf`、`domain/range`、版本信息 | **部分** | 没有 OWL 2 profile、没有 `equivalentClass`/`disjointWith`/restrictions 等 DL 公理、没有一致性/可满足性推理；外部 reasoner 需自行接入 |
| **SHACL** | `pyshacl.validate(..., inference="none")`；`SHACLGenerator` 生成 shapes | **较好** | 真实 SHACL 验证；但 `pyshacl` 是 optional extra；`inference="none"` 不启用 RDFS/OWL 推理；生成的 shapes 只覆盖基础约束；`qualifiedMinCount/qualifiedMaxCount` 解释不完整 |
| **SKOS** | `ContextGraph` 对 `skos:broader/narrower` 做环检测；`OntologyEngine` 有 SKOS CRUD/search | **部分** | 只有层级环检测；没有完整 SKOS 完整性条件；SKOS 数据存储在 triplet store，和 ContextGraph 的 SKOS 边是两套 |
| **SPARQL 1.1** | `SPARQLReasoner` 通过 triplet store 或 rdflib；Explorer `/api/sparql` 只读投影 | **部分** | 必须有 triplet store；Explorer 只读、50k 节点/边、5k 行、30s；没有 federation/update；SPARQL 缓存无失效；Explorer IRI 与 RDF 导出不一致 |
| **W3C PROV-O** | `ProvenanceManager.export_prov()` 生成 qualified association/derivation/generation/usage/invalidation | **较好（独立存储）** | 没有 `prov:wasRevisionOf`；bundle 只是 membership；决策/图事实默认不自动写 provenance；`source_document` 单值 |
| **JSON-LD / RDF 数据集** | JSONExporter 支持 top-level `@id` + `@graph`；triplet store 支持 named graph 参数 | **部分** | PROV bundle 不是 named graph；ContextGraph 没有 dataset/named graph 概念；RDF 导出没有 dataset 参数 |
| **RDF-star / SPARQL-star** | 无 | **不支持** | 不能给边再加元数据（只能 reification 或自定义 `familyId`） |

### 9.2 具体的标准偏离

#### RDF 1.1：导出验证不是语法验证

`RDFValidator.validate_rdf_syntax()` 的实现只检查：

- `rdf_data` 是 dict；
- `entities` 每项是 dict，至少有 `id` 或 `text`；
- `relationships` 每项有 source/target 字段。

它**不解析 Turtle/RDFXML/JSON-LD**，也不检查 IRI 合法性、字面量转义、datatype、language tag。`export_to_rdf()` 在验证失败时只是 warning，然后继续序列化。因此“validate before serialization”不能等同于“RDF 1.1 conformance”。来源：`rdf_exporter.py:1480-1565`、`:1780-1810`。

#### JSON-LD：实体类型被降级

`serialize_to_jsonld()` 对每个实体生成：

```json
{
  "@id": "...",
  "@type": "semantica:Entity",
  "semantica:text": "...",
  "semantica:type": "Person"
}
```

它刻意**不**把 `entity["type"]` 放进 `@type`，理由是避免 `@vocab` 把 `ORG` 展开成 `ns#ORG`（#1146）。这在工程上是防御性的，但代价是：JSON-LD 消费者看不到 `Person`/`Organization` 的类语义；`@type` 永远是 `semantica:Entity`。Turtle 路径则会把类型写成 `<...Person>`。两个序列化器对同一个图给出不同的类型语义。来源：`rdf_exporter.py:1273-1305`；`rdf_exporter.py:848-870`。

#### IRI 铸造：三套命名空间

| 表面 | 命名空间 | 证据 |
|---|---|---|
| RDF/OWL 导出 | `https://semantica.dev/ns#`；实体 id 原样或 minted `entity_<hash>` | `rdf_exporter.py:51-60`、`:140-155` |
| PROV-O 导出 | `DEFAULT_BASE_URI = https://semantica.dev/ns#` | `provenance/manager.py:52` |
| Explorer SPARQL | `http://semantica.local/entity/` / `http://semantica.local/prop/` | `explorer/routes/sparql.py:120-121` |
| JSON-LD | `https://semantica.dev/ns#`（`semantica:` context） | `json_exporter.py` / `rdf_exporter.py` |

同一个节点在导出文件、PROV 图、Explorer SPARQL 里可能是三个不同 IRI；跨表面 join 会失败。heirloom 必须定义**单一 IRI 铸造策略**（base URI、id 编码、版本化 IRI、相对/绝对规则），并让 REST/MCP/CLI/export/SPARQL 共用。

#### OWL 2：没有 DL 推理

`OntologyValidator` 的 `reasoner` 参数只是字符串；`validate()` 只做结构检查，`check_consistency`/`check_satisfiability` 是 `pass`；源码注释明确是 placeholder。`OWLGenerator` 生成的是基础 OWL 公理，不是完整 OWL 2 ontology。**不能声称 OWL 2 DL 一致性检查**；如果 heirloom 需要，必须集成外部 reasoner（HermiT/Pellet/ELK）或实现一个明确的 OWL 2 RL 子集。

#### SHACL：真实但有限

- `pyshacl` 是真实 SHACL 引擎；这是 Semantica 标准一致性最好的部分。
- `inference="none"`：不启用 RDFS/OWL 推理；如果数据没有显式 `rdf:type`，`sh:targetClass` 可能匹配不到。
- `SHACLGenerator` 生成的 shapes 覆盖 class/property、`minCount/maxCount/datatype/class`；对 `qualifiedMinCount/qualifiedMaxCount` 的解释不完整。
- `SHACLGraph` 的 `closed=True` 是 strict tier 的可选行为；默认不是 closed-world。
- `pyshacl` 不在 base install，需 `semantica[shacl]`。

#### SPARQL：只读、受限、IRI 不一致

- `SPARQLReasoner` 需要 triplet store；没有 store 直接报错，不返回空。
- Explorer `/api/sparql` 是另一套：把 `GraphSession` 投影成 rdflib；只允许 SELECT/ASK/CONSTRUCT/DESCRIBE；禁止 UPDATE 关键字；节点/边 50k、行 5k、超时 30s；投影只包含 node type/label/properties 和 edge type，不包含 provenance/decision 专用语义。
- 没有 federation、没有 SPARQL Update、没有 named graph 选择（Explorer 投影是默认图）。
- `SPARQLReasoner` 的查询缓存没有失效；数据变更后要手动 `clear_cache()`。

#### PROV-O：覆盖较好，但没有 revision 关系

- 有 `prov:qualifiedAssociation` + `hadRole`、`qualifiedDerivation`、`qualifiedGeneration`、`qualifiedUsage`、`qualifiedInvalidation`、`prov:actedOnBehalfOf`、`prov:wasInformedBy`。
- 没有 `prov:wasRevisionOf`；`previous_version_id` 只在内部 revision 链使用。
- `bundle_id` 只是 `prov:Bundle` + `hadMember`，不是 RDF named graph。
- 决策/图事实默认不写 provenance；`DecisionRecorder` 的接线坏（§5.2）。
- `source_document` 单值；多来源 corroboration 需要多条 entry 或 metadata。

---

## 10. 文档 / README / CHANGELOG / 源码不一致清单

下表只列**会改变架构判断**的差异；同一事实的多个来源都给链接。

| ID | 断言 | 文档/README/CHANGELOG | 源码/测试 | 对 heirloom 的影响 |
|---|---|---|---|---|
| C1 | Precedent search 是 semantic/hybrid | README:208 “semantic precedent search”；MCP 文档:180 “Hybrid similarity search” | `find_precedents_by_scenario()` 忽略 `use_semantic_search`；实际 0.7 Jaccard + 0.3 度数先验（`context_graph.py:4618-4740`）；官方决策指南:159 承认 lexical | 不要把“语义检索”当作 Semantica 已有能力；heirloom 需在 Python 层显式做 embedding/vector，并定义混合评分 |
| C2 | Causal chain 是“full causal ancestry” | README:209 | `trace_decision_causality()` 把共享实体+时间更早的决策当 `influences`（`context_graph.py:4889-5067`）；`analyze_decision_influence()` 同类别/共享实体都算直接影响（`:4741-4835`） | 审计/合规 API 必须区分 asserted vs inferred；不要把启发式当因果 |
| C3 | “Every fact” 有 W3C PROV-O | README:94、1166；架构文档:164 “every node and edge carries a source_id” | `ContextGraph` 源码 `provenance` 出现次数 0；`ContextNode/Edge` 没有文档来源/activity/agent 字段（`ContextEdge.source_id` 只是边起点）；`to_kg_dict()` 不含 provenance（`context_graph.py:3619-3709`）；`DecisionRecorder` 溯源接线坏（`decision_recorder.py:709-737`） | 文档的 `source_id` 与 `ContextEdge.source_id`（边起点）同名但语义不同；Provenance 必须在写入路径强制，不能做成可选旁路；heirloom 应把 provenance 作为 Fact/Decision 的必填引用 |
| C4 | 内置 OWL/HermiT/Pellet 推理 | `ontology/methods.py:53`、`ontology_usage.md:96` 声称 HermiT/Pellet 集成；`docs/guides/ontology.md:26` 又说外部工具 | `OntologyValidator` 是 placeholder（`ontology_validator.py:326-370`）；没有 `owlready2` 依赖/调用 | 不能把 OWL DL 放进 v0.1 的“确定性推理”承诺；要么集成外部 reasoner，要么明确只做 OWL 导出 + SHACL |
| C5 | RDF/JSON-LD 是标准的 | README/文档称 RDF/OWL/SHACL/JSON-LD | `RDFSerializer` 是自研字符串拼接（`rdf_exporter.py:617`）；`validate_rdf_syntax` 只查 dict 字段（`:1480`）；JSON-LD 把实体类型降级（`:1273-1305`）；Explorer SPARQL 用不同 IRI（`sparql.py:120-121`） | 用成熟 RDF 栈（oxigraph/rio/rdflib）和单一 IRI 策略；JSON-LD 必须保留 `@type` |
| C6 | “Four-layer / clean interfaces / no hidden coupling” | `docs/architecture.md:3-7` | `context ↔ kg`、`context ↔ graph_store`、`split ↔ semantic_extract`、`ontology ↔ change_management` 循环 | Rust crate 边界不能照抄四层；先画 DAG，再切 crate；用 trait 约束依赖方向 |
| C7 | Ingestion 统一为 `SourceDocument` | `docs/architecture.md:18`、`docs/glossary.md:44` | 仓库内无 `SourceDocument` 类；ingest 返回 `FileObject` / `WebContent` / `TableData` / `StreamMessage` / `EmailData` 等 | heirloom 需要定义 canonical `Document`/`Artifact` envelope；不要让每个 ingestor 返回不同类型 |
| C8 | MCP 15 tools | 官方站 + `docs/reference/mcp_server.md:172` | 实际 22 个（`TOOL_DEFINITIONS`；测试 `:44-48` 断言 22）；文档列出的 `query_graph/update_node/delete_node` 不存在 | 从单一 typed contract 生成 MCP tool list；不要手写文档 |
| C9 | REST 100+ endpoints | `ARCHITECTURE.md:67` | 83 个 `@router` 处理器；仓库无 100+ 的计数口径 | 不要用端点数做范围估算；heirloom 应设计少量资源型端点 + 查询参数，而不是 80+ 手写路由 |
| C10 | `required_categories` 策略受支持 | `docs/guides/policy-engine.md:73` | `_evaluate_compliance` 把 `required_categories` 解析成 `categories`，`Decision` 只有 `category`，通常直接返回 False（`policy_engine.py:916-958`） | 策略 DSL 必须有测试覆盖；不要相信文档规则表 |
| C11 | `ContextGraph` 自带 FAISS 语义索引 | `docs/guides/context-graphs.md:7` | `ContextGraph` 无 vector store/embedding 字段；`AgentContext` 才包 vector store（`context_graph.py:584-672`） | 图存储与向量索引应分层；不要让 `ContextGraph` 承担向量检索 |
| C12 | 版本是 v0.7 / v0.5 | README:1509 “What's New in v0.7.0”；RELEASE_NOTES:6 “0.5.0” | `pyproject.toml:7` / `__init__.py:13` 都是 0.8.0 | 文档滞后；以 `pyproject`/源码为准；引用时 pin commit |
| C13 | ContextGraph backend 不跑 evaluators | `docs/guides/decision-intelligence.md:334` | `AgentContext.record_decision` 在两个 backend 都调用 `evaluate_and_enrich_decision`（`agent_context.py:1762-1765`）；CHANGELOG Unreleased 说 both | 文档滞后；heirloom 的 evaluator hook 应在所有写入路径统一 |
| C14 | `recorded_at` ingestion 自动时间；Allen 13 关系完整 | `docs/reference/temporal.md:160-180`；`docs/index.md` / `faq.md` 说 all 13 relations | `BiTemporalFact.from_relationship` 缺省用 `valid_from` 当 `recorded_at`（`temporal_model.py:40-60`）；开放区间 `relation()` 返回 `finished_by` 而非 `contains`（`temporal_reasoning.py:48-92`）；边界闭/半开不一致（`context_graph.py:431-449`） | 时序模型必须在 Rust 核心定义唯一 interval 约定；事务时间必须由存储层生成，不能用有效时间冒充 |
| C15 | 决策时间统一 | 文档示例同时用 `datetime.now()` / ISO / epoch | `record_decision` 用 float epoch（`context_graph.py:4565`）；`add_decision(Decision)` 归一化为 ISO（`:3905-3919`）；`Decision.timestamp` 是 datetime | heirloom 用单一 `Timestamp` 类型（UTC instant + 精度），序列化统一 ISO 8601 |
| C16 | `add_entity` 的 label/metadata 是节点属性 | MCP 文档说“Add a node” | `add_entity` 把 `label`/`metadata` 传给 `**properties`，`content` 默认成 id（`mcp/tools/graph.py:18-27`；`context_graph.py:1141-1173`） | MCP/REST/CLI 都必须走同一个 typed `create_node` 命令；不要让每个表面自己拼 kwargs |
| C17 | `DecisionRecorder` 写 ContextGraph 后决策可被 precedent/impact 看到 | 文档示例 `DecisionRecorder(graph_store=graph)` | `_store_decision_node` 对 ContextGraph 直接 `add_node`（`decision_recorder.py:584-605`），`content` 默认成 `decision_id`；`add_node` 不同步 `_decisions`；`find_precedents_by_scenario` 依赖 `_decisions`（`context_graph.py:4618`） | 写入路径必须共享同一套索引/不变量；不能让“旁路写入”绕过 decision index，导致 ContextGraph 的 precedent/impact 看不到 |
| C18 | 四层模块可独立使用 | `docs/architecture.md:7` “every component independently swappable” | `import semantica.context` 会拉入 `kg/graph_store/vector_store/reasoning/...`；`core` 几乎导入全部 | Rust 核心应做 feature-gated crates；Python 层用可选 import/extra，不要把全栈塞进一个 import |

> 说明：C9 的 “100+” 取决于计数口径（route 函数 vs FastAPI operation vs 文档端点）。本档案按 `@router` 装饰器计 83；如果按 operation + 参数组合计可能更多，但仓库没有给出可复现的计数，因此不能把 100+ 当作事实。

---

## 11. 对 heirloom 的架构含义

以下是**基于上述事实的建议**，不是对 Semantica 的逐条复刻。每条都给出“为什么”。

### 11.1 不要复刻 `ContextGraph` god object；按写入模型拆服务

**事实**：`ContextGraph` 6143 行，混合了图存储、决策索引、因果遍历、precedent、impact、policy gate、retract/purge、Markdown 持久化、分析算法入口；`AgentContext` 又在其上包一层 memory/retrieval/decision。决策、节点、边共享一个可变对象和一把锁。

**建议**：Rust 核心里至少拆成：

- `GraphStore`：节点/边/属性/邻接/类型索引；
- `DecisionStore`：决策记录、因果边、precedent 索引；
- `ProvenanceStore`：append-only 事件/哈希链；
- `TemporalStore`：valid/transaction 轴、快照/版本；
- `SearchIndex`：词面/向量/混合检索；
- `ReasoningEngine`：规则/Datalog/SPARQL（按 feature gate）。

每个 store 用 trait 暴露最小接口；`DecisionStore` 不直接依赖 `GraphStore` 的内部结构，而是依赖 `NodeRef`/`EdgeRef`/`FactRef`。这样既能在单二进制内组合，也能替换后端。

### 11.2 把 `Fact` 做成一等对象，而不是“边就是事实”

**事实**：Semantica 没有统一 `Fact` ID；边身份由内容派生、`BiTemporalFact` 只是包装、reasoning `Fact` 只在 working memory、provenance 以 `entity_id` 为主键。结果是“同一条事实”在图、推理、溯源、导出里可能有不同身份。

**建议**：定义规范 `Fact`：

```text
Fact {
  id: FactId,                 // stable, content-independent
  subject: TermId,
  predicate: Iri,
  object: Term,               // entity | literal | interval
  polarity: bool,             // assert/deny
  confidence: f32,
  valid_time: Interval,       // [start, end)
  transaction_time: Interval, // recorded/superseded
  provenance: ProvenanceRef,
  derived_from: Vec<FactId>,
  status: asserted | inferred | retracted
}
```

图边可以是 Fact 的物化视图，而不是 Fact 本身。这样决策、推理、溯源、导出都引用同一个 `FactId`，审计链才闭合。

### 11.3 显式区分“断言因果”与“推断因果”

**事实**：`add_causal_relationship` 是显式断言；`trace_decision_causality` 把共享实体+时间顺序的决策也标成 `influences`；`analyze_decision_influence` 把同类别/共享实体/图可达性都算影响。

**建议**：

- `CausalEdge { source, target, kind: Asserted(CAUSED|INFLUENCED|PRECEDENT_FOR), evidence, confidence, asserted_by }`；
- 推断结果放在单独的 `Inference`/`Hypothesis` 类型里，带 `method`、`score`、`inputs`，不进入 `CausalEdge`；
- 审计 API 默认只返回 asserted；要返回 inferred 必须显式 `include_inferred=true`，并在结果里标记；
- impact analysis 若保留，命名为 `influence_estimate`，文档明确“不是因果识别”。

### 11.4 Precedent search：语义检索放 Python 层，Rust 核心保留确定性词面/结构检索

**事实**：Semantica 的 `use_semantic_search` 是空参数；实际是 Jaccard + 度数先验；官方指南承认 lexical。它的 `DecisionEmbeddingPipeline` / vector store 存在，但没有接到 `ContextGraph` 的 precedent 路径。

**建议**：

- Rust 核心提供确定性的 lexical/BM25/结构检索，返回可解释的 `ScoreBreakdown`；
- Python 处理层提供 embedding/vector/hybrid（map #1 已把 Python 定为 AI 处理层），通过 trait 注入；
- API 明确 `mode=lexical|vector|hybrid`，不要用一个布尔 flag 暗示语义；
- 为 precedent 定义离线评测集（scenario → 期望先例），记录 precision@k / recall@k，作为 v0.1 验收项。

### 11.5 时序：从存储层就区分 valid time 与 transaction time

**事实**：`ContextGraph` 只有 `valid_from/valid_until`；`TemporalGraphQuery` 有 transaction 轴，但 `to_kg_dict` 把 `recorded_at` 放进嵌套 metadata、查询层读不到；`BiTemporalFact` 缺省用 `valid_from` 当 `recorded_at`；Allen 开放区间有缺陷；边界闭/半开不一致。

**建议**：

- 存储层每个 Fact/Decision/Edge 都带两对时间：`valid_from/valid_to` 与 `recorded_at/superseded_at`；`recorded_at` 由存储层在写入时生成，绝不从 valid time 推断；
- 统一区间约定为半开 `[start, end)`，在类型/文档/测试里写死；Allen 关系对开放区间要有专门测试；
- 快照分两种：`as_of_valid(t)`（事后看当时有效）与 `as_of_transaction(t)`（当时系统认为有效）；API 名字要区分；
- 不要用整图 JSON 快照做 transaction log；用 append-only event log + 周期 checkpoint。

### 11.6 推理：v0.1 明确范围，不要承诺 OWL DL

**事实**：Semantica 的 forward chain 只支持 AND；Rete 是正则/每规则 alpha；Datalog 无否定/聚合/比较且小写化；SPARQL 依赖 triplet store；SHACL 真实但 opt-in；OWL DL 验证是 placeholder；TMS 只支持非递归固定规则。

**建议**（按 v0.1 可验收范围）：

- **Datalog 子集**：typed terms、递归、stratified negation（如果业务需要）、基本比较；明确不支持聚合/函数符号；
- **SPARQL**：用成熟引擎（oxigraph 或 rdflib）执行，不自己写解析器；只读 + 查询上限；
- **SHACL**：用 `pyshacl` 或 Rust SHACL 引擎，明确 `inference=none` 的语义；
- **OWL**：只做导出 + 结构校验；DL 一致性检查放到 v0.2+，或调用外部 reasoner；
- **规则语言**：不要用 `IF ... AND ... THEN ...` 字符串；用 typed AST，支持显式 `not`/`or`/比较，或者明确只支持 Horn 子句；
- **真值维护**：借鉴 `TruthMaintenanceSession` 的 support/derivation 模型，但从一开始就设计成可增量、可持久化、可线程安全；不要把它做成独立旁路。

### 11.7 标准：单一 IRI 策略 + 成熟 RDF 栈

**事实**：RDF/JSON-LD 是自研字符串拼接；`validate_rdf_syntax` 不解析 RDF；Explorer SPARQL 用 `semantica.local`，导出用 `semantica.dev`；JSON-LD 把实体类型降级；OWL 只导出基础公理。

**建议**：

- 定义 `IriPolicy`：base URI、id → IRI 的编码规则、版本化 IRI、相对/绝对、大小写/转义；所有 surface 共用；
- Rust 侧用 `oxrdf`/`oxigraph`/`rio` 之类成熟库做 RDF 解析/序列化；不要手写 Turtle；
- JSON-LD 必须保留 `@type` 为类 IRI，`@context` 可扩展；用 JSON-LD 1.1 处理器做验证；
- PROV-O 用 `prov:wasRevisionOf` 表达修正；bundle 用 named graph；决策/事实写入时自动生成 provenance；
- SHACL 作为可选 feature，明确依赖与失败模式；SPARQL 只读、带超时/行数/内存上限。

### 11.8 表面：一个 typed contract 生成 REST / MCP / CLI

**事实**：MCP 22 tools vs 文档 15；REST 83 routes vs 文档 100+；CLI ~91 leaf；MCP `add_entity` 的 label/metadata 形状与 `ContextGraph.add_node` 不一致；`DecisionRecorder`/`AgentContext`/`ContextGraph` 三条决策写入路径行为不同。

**建议**：

- 用 Rust 定义 `Command`/`Query`/`Event` 的 typed schema（serde + JSON Schema / OpenAPI / MCP schema 生成）；
- REST、MCP、CLI 都是同一 service 层的 adapter，不各自实现业务逻辑；
- 每个 surface 有 contract test：同一命令在 REST/MCP/CLI 上产生同样的图/决策/事件；
- 版本化 schema；MCP tool list 从注册表生成，不手写文档；
- 本地优先：默认绑定 loopback，API key/Unix socket 可选；不要默认匿名暴露。

### 11.9 性能：heirloom 自己建可复现基准，不引用 Semantica 数字

**事实**：118k/0.004ms 是 v0.5.0 历史数字；`semantica/benchmarks` 是 QA harness 且无结果表；`test_performance_benchmarks.py` 测的是决策处理阈值；Rete/Datalog/SPARQL 无公开基准。

**建议**：

- 在蓝图里定义 benchmark harness：数据集（合成 + 真实子集）、硬件、冷/热缓存、重复次数、p50/p95/p99、内存峰值、命令；
- 至少覆盖：图写入吞吐、邻居/路径查询、precedent 检索、决策写入+索引、因果链遍历、Datalog fixpoint、SPARQL 查询、快照创建/恢复；
- 把“0.004ms”这类数字当作**待验证假设**，不要写进 heirloom 的承诺；
- Rust 核心的优势应体现在：并发读写、内存布局、索引、零拷贝序列化、可预测延迟；用基准证明，而不是语言叙事。

### 11.10 Explorer：借鉴信息架构，不复制实现

**事实**：Explorer 是 React 19 + Sigma.js + FastAPI；六个 workspace（Knowledge Explorer / Analyze / Decisions / Enrich / Manage / Ontology Hub）；Graph 页有搜索、Ego、heatmap、timeline、inspector、Markdown 编辑；CLI 整文件加载、>10k 节点警告；SPARQL 50k/5k/30s 上限；单 GraphSession、单 API key。

**建议**：

- 采用 workspace IA：Explore（图/记忆/词表）、Analyze（SPARQL/推理）、Decisions（链/先例/合规）、Enrich（导入/合并/registry/entity resolution）、Manage（lineage/overview/ontology）、Ontology Hub；
- 图视图保留 canvas + timeline + inspector + 搜索/路径/邻居/Ego/heatmap；
- 但后端必须分页/服务端搜索/增量加载，不能整 JSON 进浏览器；节点上限用采样/聚合，而不是“>10k 就别用”；
- 决策 workspace 应同时提供 precedent/impact/compliance 的只读视图，而不仅是 causal chain（Semantica 的 REST 有这些端点，UI 没有）；
- TypeScript 工作台与 Rust/Python 之间用 typed API（OpenAPI/JSON Schema），不要复制 FastAPI 的 Pydantic 模型。

### 11.11 迁移：Semantica 导出是有损的

**事实**：`ContextGraph.to_kg_dict()` 只导出 id/text/type/properties/metadata/validity；不导出 `_decisions` 的完整字段、provenance、transaction time、retraction/tombstone、decision indexes；`save_to_file` 是 Semantica 自有 JSON 格式。

**建议**：

- 如果 v0.1 要做 Semantica 导入，先定义映射表：node → Entity/Fact、edge → Fact、Decision node → Decision、metadata → properties、validity → valid time；明确丢弃/保留哪些字段；
- 把 `provenance`、`transaction_time`、`retraction` 标为“导入时不可恢复，除非源系统另有导出”；
- 迁移工具应输出报告：导入数量、跳过数量、丢失字段、无法映射的 node_type/edge_type；
- 不要让“兼容 Semantica”成为 v0.1 的硬约束；map #1 已明确“不做 API 平替”。

---

## 12. 开放问题与建议的验证计划

### 12.1 对 heirloom 仍需决定的问题

1. **Fact 的身份与版本**：`FactId` 是内容哈希还是 ULID？修正一条事实是“新 Fact + supersedes”还是“同 Fact 新版本”？这决定 provenance/bitemporal 的模型。
2. **证据模型**：决策/事实如何引用文档、chunk、表格行、网页快照？是 `EvidenceRef` 表，还是把 artifact 也做成 Fact？Semantica 没有答案。
3. **OWL DL 是否在 v0.1 范围**：如果不在，明确“OWL 导出 + SHACL 校验，不做 DL 推理”；如果要，选外部 reasoner 还是 OWL 2 RL 子集。
4. **规则语言**：Horn 子句 + 分层否定，还是更完整的 Datalog；是否支持聚合/比较；这决定 Rust 推理引擎的复杂度。
5. **向量检索边界**：embedding 在 Python 层生成，Rust 核心只存向量/做 ANN，还是 Rust 也做 embedding？map #1 把 Python 定为 AI 处理层，建议后者不做。
6. **快照/事务日志**：append-only event log + checkpoint，还是 periodic full snapshot；是否需要 PITR（point-in-time recovery）。
7. **Semantica 导入**：是否 v0.1 必须；如果必须，接受哪些丢失（provenance/transaction/retraction）。
8. **MCP 工具面**：v0.1 要暴露多少？Semantica 22 个工具已经出现文档漂移；heirloom 应从 typed contract 生成，并考虑只读/写入分组。
9. **Explorer 范围**：v0.1 是“只读图 + 决策 + 时间轴”，还是完整 Ontology Hub；Ontology Hub 的 36 个端点成本很高。
10. **许可证/归属**：Semantica 是 MIT，heirloom 是 Apache 2.0；参考设计没问题，若复制代码需保留 MIT 声明。建议 clean-room 实现，只在文档中引用。

### 12.2 建议的后续验证（按优先级）

| 优先级 | 验证 | 方法 | 预期产出 |
|---|---|---|---|
| P0 | 真实运行 `DecisionRecorder` + `ProvenanceManager`，确认溯源是否写入 | 建 venv，`pip install semantica`，用真实 `ProvenanceManager(storage_path=...)`，调用 `DecisionRecorder.record_decision`，检查 `get_lineage` / SQLite 行 | 确认 `track_entity` TypeError / `track_activity` AttributeError 是否如静态分析 |
| P0 | `PolicyEngine.required_categories` 复现 | 构造 `Decision(category="credit_risk")` 和 `Policy(rules={"required_categories":["credit_risk"]})`，调 `check_compliance` | 确认是否恒 False |
| P0 | `find_precedents_by_scenario` 是否真的忽略 `use_semantic_search` | 用两个语义相近但词面不同的决策，分别传 True/False；对比结果 | 确认无差异，且与向量检索无关 |
| P1 | Allen 开放区间/边界 | 用真实 `TemporalReasoningEngine` 跑 4 组开放/闭合区间；对比 `relation`/`contains`/`active_at`/`ContextNode.is_active` | 确认 `finished_by` 与边界不一致 |
| P1 | RDF/JSON-LD 导出可解析性 | 用 rdflib 解析 Turtle/RDFXML/JSON-LD 输出；用 pyshacl 验证生成的 shapes | 找出语法/类型/IRI 问题 |
| P1 | MCP `add_entity` label/metadata | 启 MCP server，调用 `add_entity`，再用 `query_graph`/export 查看节点 content/properties | 确认 label 是否丢失、metadata 是否嵌套 |
| P2 | Explorer route count / MCP tool count | 启动 server，读 `/openapi.json`；读 MCP `tools/list` | 得到运行时计数，和源码计数对比 |
| P2 | 性能复现 | 生成/导入 118k 节点图，按 README 硬件/查询跑 search/BFS/SPARQL；记录 p50/p95/内存 | 判断 0.004ms/<50ms 是否可复现 |
| P2 | 数据模型迁移 | 用 `to_kg_dict()` 导出含 decisions/provenance 的图，检查丢失字段 | 形成 Semantica → heirloom 映射表 |

### 12.3 研究局限

- 本档案是**静态源码研究 + 隔离复现**，不是完整运行时验证；P0 项应尽快在 venv 中确认。
- 版本行号以 `3ebdb95` 为准；Semantica 迭代很快，后续 commit 可能修复部分缺陷（例如 `track_activity`、`required_categories`、Allen 开放区间）。
- 官方文档站可能比仓库 `docs/` 滞后；本档案优先以源码为准，文档冲突处已标注。
- 没有评估 Semantica 的抽取质量、嵌入质量、LLM provider 质量；这些不属于本票的架构模型范围。

---

## 附录 A：关键源码索引

| 主题 | 文件 | 关键行 |
|---|---|---|
| 四层架构 | `docs/architecture.md` | 3-98 |
| ContextGraph 字段/初始化 | `semantica/context/context_graph.py` | 564-672 |
| ContextNode/Edge | `semantica/context/context_graph.py` | 420-530 |
| `record_decision` | `semantica/context/context_graph.py` | 4450-4617 |
| `_add_decision_to_graph` | `semantica/context/context_graph.py` | 5123-5255 |
| `add_decision(Decision)` | `semantica/context/context_graph.py` | 3804-3934 |
| `add_causal_relationship` | `semantica/context/context_graph.py` | 3934-4006 |
| `find_precedents_by_scenario` | `semantica/context/context_graph.py` | 4618-4740 |
| `analyze_decision_influence` | `semantica/context/context_graph.py` | 4741-4835 |
| `trace_decision_causality` | `semantica/context/context_graph.py` | 4889-5067 |
| `enforce_decision_policy` / `check_decision_rules` | `semantica/context/context_graph.py` | 5067-5122 / 6057-6075 |
| `state_at` | `semantica/context/context_graph.py` | 3760-3803 |
| `to_kg_dict` | `semantica/context/context_graph.py` | 3619-3709 |
| Decision 模型 | `semantica/context/decision_models.py` | 87-360 |
| DecisionRecorder | `semantica/context/decision_recorder.py` | 154-737 |
| ProvenanceEntry | `semantica/provenance/schemas.py` | 37-190 |
| ProvenanceManager | `semantica/provenance/manager.py` | 79-1610 |
| `track_entity` | `semantica/provenance/manager.py` | 291-478 |
| `export_prov` | `semantica/provenance/manager.py` | 1294-1456 |
| `verify_chain` | `semantica/provenance/manager.py` | 1506-1610 |
| BiTemporalFact | `semantica/kg/temporal_model.py` | 28-65 |
| Allen 引擎 | `semantica/kg/temporal_reasoning.py` | 24-120 |
| TemporalGraphQuery | `semantica/kg/temporal_query.py` | 41-169 |
| 时间轴映射 | `semantica/kg/temporal_query.py` | 752-761 |
| `create_snapshot` | `semantica/kg/temporal_query.py` | 1336-1500 |
| Pipeline 模型 | `semantica/pipeline/pipeline_builder.py` | 56-230 |
| ExecutionResult | `semantica/pipeline/execution_engine.py` | 53-80 |
| AgentMemory | `semantica/context/agent_memory.py` | 128-245, 323-585 |
| Ontology 生成 | `semantica/ontology/ontology_generator.py` | 124-155, 793-900 |
| OWL 生成 | `semantica/ontology/owl_generator.py` | 216-335 |
| OWL 验证占位 | `semantica/ontology/ontology_validator.py` | 299-370 |
| SHACL 验证 | `semantica/ontology/ontology_validator.py` | 276-397 |
| Forward chaining | `semantica/reasoning/reasoner.py` | 447-680, 751-797 |
| Rete | `semantica/reasoning/rete_engine.py` | 359-700 |
| Datalog | `semantica/reasoning/datalog_reasoner.py` | 39-430 |
| SPARQL | `semantica/reasoning/sparql_reasoner.py` | 53-430, 666-727 |
| Truth maintenance | `semantica/reasoning/truth_maintenance.py` | 148-520 |
| PolicyEngine | `semantica/context/policy_engine.py` | 80-418, 906-1048 |
| AgentContext | `semantica/context/agent_context.py` | 91-313, 1690-1960 |
| RDF 导出 | `semantica/export/rdf_exporter.py` | 51-60, 617-730, 800-1450, 1480-1565 |
| JSON 导出 | `semantica/export/json_exporter.py` | 300-480 |
| Explorer 路由 | `semantica/explorer/routes/*.py` | 13 个 router |
| Explorer SPARQL | `semantica/explorer/routes/sparql.py` | 120-190, 209-300 |
| Explorer 搜索索引 | `semantica/explorer/search_index.py` | 97-190 |
| MCP 工具定义 | `semantica_mcp/mcp/tools/*.py` | 22 个 |
| MCP 文档 | `docs/reference/mcp_server.md` | 172-190 |
| CLI | `semantica/cli.py` | 602-6925 |
| Explorer 前端 IA | `explorer/src/App.tsx` | 84-91 |
| Graph workspace | `explorer/src/workspaces/GraphWorkspace/GraphWorkspace.tsx` | 1267-1312, 1790-1810 |
| Timeline | `explorer/src/workspaces/GraphWorkspace/TimelinePanel.tsx` | 60-180 |
| 性能主张 | `README.md` | 1178-1187 |
| Benchmarks harness | `semantica/benchmarks/README.md` | 1-12 |
| 测试规模 | `tests/` | 502 个 `test_*.py` |

## 附录 B：复现命令

```bash
# 1. 克隆
git clone --depth 50 https://github.com/semantica-agi/semantica.git
cd semantica
git log -1 --format='%H %ci %s'

# 2. 模块依赖（粗略）
python3 - <<'PY'
import os, re
mods = [d for d in os.listdir('semantica') if os.path.isdir(f'semantica/{d}')]
deps = {m: set() for m in mods}
for m in mods:
    for root, _, files in os.walk(f'semantica/{m}'):
        for f in files:
            if not f.endswith('.py'):
                continue
            s = open(os.path.join(root, f), encoding='utf-8', errors='ignore').read()
            for mm in re.findall(r'from\s+\.\.([a-z_]+)', s):
                deps[m].add(mm)
            for mm in re.findall(r'from\s+semantica\.([a-z_]+)', s):
                deps[m].add(mm)
for m in mods:
    d = sorted(x for x in deps[m] if x in mods and x != m)
    if d:
        print(f'{m}: {", ".join(d)}')
PY

# 3. REST 路由计数
grep -h '@router\.\(get\|post\|put\|delete\|patch\)' semantica/explorer/routes/*.py | wc -l

# 4. MCP 工具计数
grep -c '"name":' semantica_mcp/mcp/tools/*.py | awk -F: '{s+=$2} END {print s}'

# 5. 测试规模
find tests -name 'test_*.py' | wc -l
find tests -name '*.py' -print0 | xargs -0 cat | wc -l

# 6. Allen 开放区间隔离复现
mkdir -p /tmp/allencheck && cd /tmp/allencheck
cp semantica/kg/temporal_reasoning.py .
cp semantica/kg/temporal_model.py .
# 将 `from .temporal_model` 改为 `from temporal_model`；
# 将 `from ..utils.exceptions import TemporalValidationError` 替换为本地异常类；
python3 - <<'PY'
from temporal_reasoning import TemporalReasoningEngine, TemporalInterval
from temporal_model import TemporalBound
from datetime import datetime, timezone
e = TemporalReasoningEngine()
a = TemporalInterval(datetime(2020,1,1,tzinfo=timezone.utc), TemporalBound.OPEN)
b = TemporalInterval(datetime(2021,1,1,tzinfo=timezone.utc), TemporalBound.OPEN)
print(e.relation(a, b).value)  # finished_by
print(e.contains(a, b))        # True
PY
```

## 附录 C：官方文档 URL 索引

- 首页：<https://docs.getsemantica.ai/>
- 架构：<https://docs.getsemantica.ai/architecture>
- 决策智能：<https://docs.getsemantica.ai/guides/decision-intelligence>
- 策略引擎：<https://docs.getsemantica.ai/guides/policy-engine>
- 推理：<https://docs.getsemantica.ai/guides/reasoning>
- 时序参考：<https://docs.getsemantica.ai/reference/temporal>
- 溯源参考：<https://docs.getsemantica.ai/reference/provenance>
- MCP：<https://docs.getsemantica.ai/reference/mcp_server>
- Explorer：<https://docs.getsemantica.ai/reference/explorer>
- 本体：<https://docs.getsemantica.ai/reference/ontology>
- 导出：<https://docs.getsemantica.ai/reference/export>
- 真值维护：<https://docs.getsemantica.ai/reference/truth_maintenance>

---

## 结语

Semantica 是一个**产品化程度高、功能面很宽、但内部模型不够收敛**的 Python 语义层。它最值得 heirloom 借鉴的是：

- 四层/多 workspace 的产品叙事；
- 决策作为图节点、因果边、precedent/impact 的 API 形态；
- PROV-O 的 qualified 关系建模；
- 真值维护的限制声明方式；
- Explorer 的信息架构（Explore/Analyze/Decisions/Enrich/Manage/Ontology Hub）。

它最需要 heirloom **不要照抄**的是：

- `ContextGraph` god object 与多套写入路径；
- “边就是事实”、没有 Fact 身份；
- 可选/旁路 provenance；
- 词面检索冒充语义检索；
- 显式与推断因果混在一个字段；
- 自研 RDF/JSON-LD 序列化与多套 IRI；
- 文档/README/源码之间的漂移。

如果 heirloom 在 v0.1 就把 **Fact 身份、provenance 强制、valid/transaction 双轴、显式 vs 推断因果、单一 typed contract、可复现基准** 这六件事定死，就能避开 Semantica 当前的大部分结构性问题，同时保留它在产品形态上的优点。
