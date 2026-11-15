# Rust 语义与基础设施栈选型

> **Ticket**: [#3 研究：Rust 语义与基础设施栈选型](https://github.com/0xnicholas/heirloom/issues/3) · **Map**: [#1 heirloom 架构蓝图](https://github.com/0xnicholas/heirloom/issues/1)
> **状态**: 研究结论，待 MAP 决策 · **日期**: 2026-11-15
> **范围**: RDF/SPARQL/SHACL/ShEx/OWL/Datalog/图算法/向量索引/嵌入式存储/Arrow/RPC/PyO3/可观测性

## 方法与边界

- 版本、下载量、发布日期取自 **crates.io API**（2026-11-15）；活跃度取自 **GitHub API**（最近 release 与默认分支 push 时间）；行为与限制取自**项目 README、docs.rs、源码与 W3C 规范**。
- **本次没有运行独立基准测试**，也没有编译整套候选。表中的“性能”是设计特征或项目自述，不是本机实测；需要落地前用 heirloom 自己的语料复测（见 §6）。
- 许可证判断只覆盖**直接依赖**；传递依赖仍需 `cargo-deny` / `cargo-about` 审计（见 §5）。
- 成熟度图例：`生产级` = ≥1.0，或版本号虽 <1.0 但已多年广泛部署、API 实际稳定；`稳定 0.x` = 广泛使用但 API 仍可能破坏；`新兴` = <0.5 或近两年出现、采用有限；`实验` = 早期/未验证；`停更` = 超过 12 个月无 release 或仓库归档。

## 1. 结论摘要

### 1.1 每类首选与备选

| 能力 | 首选 | 备选 | v0.1 决策 |
|---|---|---|---|
| RDF 模型与解析/序列化 | `oxrdf` + `oxrdfio`/`oxttl`/`oxjsonld` | `sophia` | 用 Oxigraph 家族；不引入第二套 RDF 模型 |
| SPARQL 1.1 查询与更新 | `oxigraph` Store | `spareval`（自定义数据集） | Oxigraph 单栈；`sophia_sparql` 仅实验 |
| SHACL 校验 | `shacl` 0.3.x（rudof 拆出的校验器） | `rudof_lib` 全套 | SHACL Core 走 `shacl`；ShEx 不进 v0.1 关键路径 |
| ShEx 校验 | `shex_validation` 0.3.x | `rudof_lib` | 仅保留适配点，不做 |
| OWL 2 / RDFS 推理 | **自研 RDFS + OWL 2 RL 规则层**（SPARQL CONSTRUCT + PROV-O） | `reasonable`（OWL 2 RL 子集） | 不承诺 OWL 2 DL；规则即数据 |
| Datalog / 前向链 | `ascent`（固定内置规则）+ SPARQL CONSTRUCT（用户规则） | `datafrog` / `crepe` | 证明树自己编码；增量 TMS 后置 |
| 图算法 | `petgraph` + `rayon` | `graph`（CSR/十亿边） | 内存投影图，不做第二套属性图存储 |
| 向量索引 | `hnsw_rs` | `arroy`（LMDB 持久化） | 进程内 HNSW；外部向量库不做 |
| 嵌入式存储/事务 | Oxigraph（RDF，RocksDB）+ `redb`（应用状态） | `rusqlite`（需要 SQL/FTS 时） | 两个存储，无跨库事务；用 outbox 协调 |
| 列式/进程间交换 | `arrow` + `arrow-ipc` + `parquet` | DataFusion / Arrow Flight | Arrow IPC 为主；Flight 仅 gRPC 场景 |
| 服务/RPC | `axum`（REST）+ `rmcp`（MCP） | `tonic`（gRPC/Flight） / `jsonrpsee` | REST + stdio/HTTP MCP；gRPC 可选 |
| Python 互操作 | `pyo3` + `maturin` + `arrow-pyarrow` | 子进程 + Arrow IPC | **Python 处理层独立进程**；PyO3 做 SDK/可选嵌入 |
| 可观测性 | `tracing` + `tracing-subscriber` | `opentelemetry` + OTLP | 默认结构化日志；OTLP 可选 feature |

### 1.2 十条关键判断

1. **Oxigraph 能同时承担 RDF 存储 + SPARQL 查询 + 事务，但有明确边界**：嵌入模式是 `Store::new()`（内存）或 `Store::open(path)`（RocksDB 磁盘）；事务是 repeatable-read、原子、**写事务持锁且把全部变更缓存在内存**；同一数据库目录**只能有一个读写实例**；它适合 v0.1 的 RDF 主存储，不适合当通用 OLTP 库。[Store docs](https://docs.rs/oxigraph/latest/oxigraph/store/struct.Store.html)
2. **`oxrdf` 家族是 RDF 模型/解析的默认选择**；`rio` 的 RDF 分支已归档，crates.io 上名为 `rio` 的 crate 实际是 **GPL-3.0 的 io_uring 绑定**，不是 RDF 库——这是选型时最容易踩的许可证坑。[rio_api](https://crates.io/crates/rio_api) · [rio](https://crates.io/crates/rio)
3. **`sophia` 是可靠的 RDF 工具箱，但不是成熟 SPARQL 引擎**：它有模型、解析器、规范化、同构、RDFS reasoner；`sophia_sparql` 仍是“partial”且下载量只有 3.7k，不能作为 v0.1 查询层。[sophia README](https://github.com/pchampin/sophia_rs)
4. **rudof 的 `shacl` crate 足以支撑 v0.1 的 SHACL Core 校验**：测试树里声明了 118 个 W3C data-shapes 用例、只有 4 个 SHACL-SPARQL 用例 `#[ignore]`，Native/Sparql 两种模式都跑；但它是 0.3.x、Native 模式要求整图在内存、SHACL-SPARQL 只部分覆盖。[shacl tests](https://github.com/rudof-project/rudof/tree/master/shacl/tests)
5. **ShEx 有实现、有官方测试套件，但不建议进 v0.1**：`shex_validation` 0.3.39 与 `shex_testsuite` 都在 rudof 仓库内；项目 README 记录的默认套件结果是 **1062 passed / 82 failed / 22 skipped / 0 not implemented**（ShExC；ShExJ 类似），说明覆盖高但并非全通过，且本次未独立跑该套件。[shex_testsuite](https://github.com/rudof-project/rudof/tree/master/shex_testsuite)
6. **Rust 里没有可放心用于 Apache 2.0 分发的 OWL 2 DL reasoner**：较活跃的 `rustdl`/`owl-dl-reasoner` 仍是 0.4.x、约 1k 下载，且硬依赖 **LGPL-3.0 的 `horned-owl`**；`reasonable` 是 BSD-3 的 OWL 2 RL 子集（其规则表自述 35 supported / 9 partial / 18 unsupported），没有证明树。[reasonable rules](https://github.com/gtfierro/reasonable/blob/master/docs/owl-rules/index.rst)
7. **`ascent`/`crepe` 本身不提供证明树或增量真值维护**：它们把规则编译成 Rust，能做半朴素求值、分层否定、lattice、并行；证明树要自己加“推导列 + premise 关系”，增量删除/重推导要自己实现 DRed 或上 differential-dataflow。v0.1 用**批量重算 + 显式 provenance**最稳。[ascent README](https://github.com/s-arash/ascent) · [crepe README](https://github.com/ekzhang/crepe)
8. **向量索引选 `hnsw_rs`**：纯 Rust、MIT/Apache、支持多线程插入/查询、dump/reload、mmap、过滤；`usearch` 的 README 明确要求 **Rust 1.100 + CMake 3.28 + C++20**（本机当前 1.98.1，按其声明不满足；需升级或核实文档是否笔误），`lancedb` 依赖 DataFusion/Lance/AWS SDK 过重，`qdrant-client` 需要外部服务。[usearch Rust README](https://github.com/unum-cloud/usearch/blob/main/rust/README.md)
9. **存储用 Oxigraph + redb，不要直接上 RocksDB/sled**：redb 是纯 Rust、ACID、MVCC、稳定文件格式，基准与 LMDB/RocksDB 同量级（单条写更快、随机读慢于 LMDB）；sled 仍是 beta 重写、2024-10 后无 release；DuckDB 只做分析导出，不做事务主库。[redb README](https://github.com/cberner/redb) · [sled](https://crates.io/crates/sled)
10. **许可证上，推荐栈全部可用 Apache 2.0 分发**：MIT/Apache/BSD-3 均可；需要避开的只有 `rio`（GPL-3.0）、`horned-owl` 及传递依赖它的 `rustdl`（LGPL-3.0）；`oxrocksdb-sys` 是 `GPL-2.0-only OR Apache-2.0` 双许可，选 Apache 分支并保留 NOTICE 即可。[oxrocksdb-sys](https://crates.io/crates/oxrocksdb-sys)

### 1.3 对“单二进制”的影响

Rust 语义核心可以打成单二进制（Oxigraph 静态链接 RocksDB、redb、hnsw_rs、axum、rmcp）。但 **Python AI 处理层不应塞进同一个进程**：GIL、torch/transformers 的依赖冲突、崩溃隔离和热更新都会让“单二进制”变成运维负担。建议 v0.1 交付 **Rust 单二进制 + 受管的 Python sidecar**，两者用 Arrow IPC（进程内则用 PyO3 + Arrow C Data Interface）通信；这是对 Map 中“嵌入式单二进制部署”的一处有意收窄，需要在架构蓝图中明确记录。

## 2. 候选矩阵

> 表中 crate 名称均可在 `https://crates.io/crates/<name>` 找到；版本、发布日期、下载量与 license 字段来自 crates.io API（2026-11-15）。仓库活跃度来自 GitHub API。为控制篇幅，表格只给关键来源链接，具体项目链接见 §7。

### 2.1 RDF 数据模型与解析/序列化

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `oxrdf` | 0.3.5（2026-10-18） | MIT OR Apache-2.0 | release 10 月；仓库 push 11-12；2.09M 下载 / 753k 近期 | 稳定 0.x | RDF 1.1 术语/三元组/数据集；借用视图 `NamedNodeRef`/`TripleRef`；RDF 1.2 在 `rdf-12` feature 后 | 0.x API；模型本身不含解析器 | **首选模型** |
| `oxrdfio` + `oxttl` + `oxrdfxml` + `oxjsonld` | 0.2.7 / 0.2.5 / 0.2.5 / 0.2.7（2026-10） | MIT OR Apache-2.0 | 同上；1.1M–1.8M 下载 | 稳定 0.x | 流式 Turtle/TriG/N-Triples/N-Quads/N3、RDF/XML、JSON-LD（含 streaming profile） | 各子 crate 版本需与 `oxrdf` 对齐 | **首选解析/序列化** |
| `sophia` | 0.10.0（2026-05-19） | Apache-2.0 OR CECILL-B | release 5 月；仓库 push 09-30；546k 下载 / 67k 近期 | 稳定 0.x | 泛型 trait 模型、RDF 1.2、Turtle/JSON-LD/XML、规范化、同构、RDFS reasoner；SPARQL 仅 partial | 与 Oxigraph 是两套模型，转换成本；生态较小；`sophia_sparql` 实验性 | **备选**（需要其 trait/规范化/同构时） |
| `rio_api` / `rio_turtle` / `rio_xml` | 0.8.6（2026-04-11） | Apache-2.0 | 仓库已归档；停更 | 停更 | 曾是流式解析的事实标准；性能口碑好 | crate 描述直接写 “Unmaintained crate, use oxrdf/oxttl”；无安全更新 | **淘汰** |
| `rudof_rdf` | 0.3.39（2026-11-10） | MIT OR Apache-2.0 | 高（随 rudof 发布） | 0.x 内部层 | 对 `oxrdf`/`oxigraph` 的封装，服务 SHACL/ShEx | API 随 rudof 变动；不是通用 RDF 模型 | **不作为通用模型** |
| `rdf-types` | 1.0.0（2026-07-07） | MIT OR Apache-2.0 | 中；835k 下载 | 生产级 1.0 | 泛型、`no_std` 友好 | 与 Oxigraph/rudof 生态不互通，需全量转换 | 仅在 `no_std` 需求出现时评估 |

**关键事实**

- `oxrdf` 明确把自己定位为“其他 crate（Oxigraph、Spargebra）的基础构建块”，解析/序列化应使用 `oxrdfio`，而不是自己拼 `oxrdf`。[oxrdf docs](https://docs.rs/oxrdf/latest/oxrdf/)
- `oxjsonld` 支持普通 JSON-LD（需整文件缓冲）与 **JSON-LD Streaming** profile（可避免缓冲）；这对大体量 JSON-LD 接入很重要。[oxjsonld README](https://github.com/oxigraph/oxigraph/tree/main/lib/oxjsonld)
- `rio_api`/`rio_turtle` 的 crates.io 描述就是“Unmaintained crate, use oxrdf / oxttl”，仓库 `oxigraph/rio` 已归档；不要因为“历史性能好”继续用。[rio_api](https://crates.io/crates/rio_api) · [oxigraph/rio](https://github.com/oxigraph/rio)
- 名为 `rio` 的 crate 是 **GPL-3.0** 的 io_uring 绑定，不是 RDF 的 Rio；在 `Cargo.toml` 里写 `rio = ...` 会直接引入 GPL-3.0。[rio](https://crates.io/crates/rio)

### 2.2 SPARQL 1.1 查询与更新

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `oxigraph` | 0.5.13（2026-10-22） | MIT OR Apache-2.0 | release 10 月；仓库 push 11-12；1.0M 下载 / 322k 近期 | 稳定 0.x | SPARQL 1.1 Query/Update/Federated Query；RocksDB 持久化；事务/快照；查询解释与统计；RDF 规范化 | README 自述“SPARQL query evaluation has not been optimized yet”；C++/RocksDB 构建；单写实例；事务内存 | **首选** |
| `spareval` | 0.2.9（2026-10-22） | MIT OR Apache-2.0 | 高；924k / 340k | 稳定 0.x | 从 Oxigraph 拆出的 SPARQL evaluator；可对自定义 `QueryableDataset` 执行 | 只给求值器，不含存储/优化器/更新协议；API 0.x | **备选/组件**（自定义数据集时） |
| `sophia_sparql` | 0.10.0（2026-05-19） | Apache-2.0（或 CECILL-B） | 低；3.7k 下载 / 263 近期 | 实验 | “currently partial” 的 SPARQL 1.2 Query 实现 | 覆盖不完整、无更新、采用极少 | **实验，不进 v0.1** |
| `spargebra` | 0.4.8（2026-10-18） | MIT OR Apache-2.0 | 高；752k / 392k | 稳定 0.x | 仅 SPARQL 解析器/AST | 不是查询引擎；需要自己实现求值 | 组件，不单独选型 |
| `rudof` 的 SPARQL 命令 | 0.3.39 | MIT OR Apache-2.0 | 高 | 0.x | CLI 可查 RDF/远端 endpoint | 依赖 rudof 全套；不是库级查询引擎 | 不作为主查询层 |

**判断**

- **Oxigraph 是唯一同时覆盖“持久化 RDF 数据集 + SPARQL 1.1 Query + Update + 事务”的 Rust 候选**。`spareval` 适合“我已有自己的图/数据集，只想复用 SPARQL 求值器”的场景，但它不解决存储和更新。
- `sophia` 的主营是 RDF 工具箱，不是 SPARQL 数据库；`sophia_sparql` 是新增的 partial 实现，下载量和使用证据都不足以承担核心查询。
- `spargebra` 只应作为解析组件出现；把它当查询引擎会导致重复实现 Oxigraph 已做的工作。

### 2.3 SHACL / ShEx 校验

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `shacl`（rudof 拆出） | 0.3.39（2026-11-10） | MIT OR Apache-2.0 | 高；60k 下载 / 55k 近期 | 稳定 0.x | Native（内存图，Rust 约束求值）与 Sparql（远端 endpoint）双模式；覆盖 SHACL Core 约束族；SHACL-SPARQL 在 `sparql` feature 后 | 0.x API；Native 需整图入内存；SHACL-SPARQL 4 个用例被 `#[ignore]`；默认 feature 会拉 Oxigraph/tokio/reqwest | **首选（SHACL Core）** |
| `shacl_validation` | 0.2.12（2026-04-22） | MIT OR Apache-2.0 | 低；49k / 5k；已被重构替代 | 旧版 | 旧引擎（`shacl_engine`/`validation_cache`） | 与 `shacl` 并存易混用；新项目不应选 | **淘汰（旧版）** |
| `rudof_lib` | 0.3.39（2026-11-10） | MIT OR Apache-2.0 | 高；39k / 7.5k | 稳定 0.x | 全套：RDF、SHACL、ShEx、DCTAP、PG-Schema、CLI、MCP | 依赖重、API 面大、0.x；适合做后备/CLI | **备选（全套）** |
| `shex_validation` | 0.3.39（2026-11-10） | MIT OR Apache-2.0 | 高；50k 下载 | 稳定 0.x | ShExC/ShExJ、RBE；仓库内带官方 ShEx 测试套件 harness | 本次未跑套件；ShEx 复杂、与 SHACL 二选一即可 | **可选，不进 v0.1** |

**关键证据**

- `shacl` 的约束模块覆盖了 SHACL Core 的主要族：`cardinality`、`logical`、`node_expr_based`、`non_shape`、`other`（closed/hasValue/in）、`property_pair`、`shape_based`（node/qualifiedValueShape）、`string_based`、`value`（class/datatype/nodeKind）、`value_range`；另有 `sparql` 约束目录。[源码树](https://github.com/rudof-project/rudof/tree/master/shacl/src/validator/constraints)
- 测试树中声明了 **118** 个 data-shapes 用例，**4** 个 SHACL-SPARQL 用例标了 `#[ignore]`；`validate_runner` 会分别用 `Native` 和 `Sparql` 两种模式跑并比较完整报告（忽略 `sh:detail`）。[测试代码](https://github.com/rudof-project/rudof/blob/master/shacl/tests/common/runner/validate.rs)
- CI 在 `submodules: recursive` 后跑 `cargo test --workspace`，因此这个测试 target 是 CI 的一部分，不是“仓库里放着但没人跑”。[CI](https://github.com/rudof-project/rudof/blob/master/.github/workflows/ci.yml)
- `shacl` 的 README 明确：Native 模式适合“数据已在进程内”，Sparql 模式适合“数据不能整图加载、只能查远端 endpoint”。[shacl docs](https://docs.rs/shacl/latest/shacl/)

### 2.4 OWL 2 / RDFS 推理

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 覆盖/性能 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| **自研 RDFS + OWL 2 RL 规则层** | — | 自有（Apache-2.0） | — | — | 用 SPARQL CONSTRUCT/UPDATE 在 Oxigraph 上跑固定规则；每条推导写 PROV-O 记录 | 需要自己写规则、处理终止/分层；性能不如编译型 Datalog | **首选（v0.1）** |
| `reasonable` | 0.4.4（2026-09-17） | BSD-3-Clause | 中；仓库 push 10-25；143k 下载 / 4.8k 近期 | 稳定 0.x | OWL 2 RL **子集**：规则表 35 supported / 9 partial / 18 unsupported；README 称 Brick 模型上比 Allegro 快约 7x、比 OWLRL 快约 38x（厂商自述） | 无证明树；删除后退化为全量重算；依赖已停更的 `rio_api`/`rio_turtle`；独立数据模型 | **备选/交叉验证** |
| `sophia_reasoner` | 0.10.0（2026-05-19） | Apache-2.0 | 低；138 下载 / 55 近期 | 实验 | 仅 Simple/RDF/RDFS entailment | 采用极少、无 OWL；不适合作为推理层 | 实验 |
| `rustdl` / `owl-dl-reasoner` | 0.4.42（2026-10-25） | Apache-2.0 OR MIT（顶层） | 高；仓库 push 11-15；约 1k 下载 / 933 近期；20 stars | 实验 | OWL 2 DL（SROIQ）混合 saturation+tableau；覆盖野心大 | **硬依赖 `horned-owl`（LGPL-3.0）**；0.4.x、采用极少、无独立 conformance 证据 | **不进 v0.1** |
| `horned-owl` | 5.0.0（2026-11-13） | **LGPL-3.0** | 高；112 stars；87k 下载 | 稳定 0.x | OWL 2 结构解析（Functional/Turtle 等），不做推理 | LGPL 静态链接对 Apache-2.0 二进制不友好；需 relinkable object | **避免** |
| `owl` | 0.0.3（2018-02-02） | Apache-2.0 | 停更 | 实验/死项目 | 早期 OWL 工具 | 8 年无维护 | **淘汰** |
| `purrdf-entail` | 4.0.2（2026-11-11） | MIT OR Apache-2.0 OR MulanPSL-2.0 | 高；5.9k 下载 / 3.7k 近期 | 新兴 | RDFS/OWL 2 RL/D 前向物化，带 reasoning report | 4.0 RC、生态很大、未验证 | **观察名单** |
| `rdfs-certificate` | 0.1.1（2026-11-03） | MIT OR Apache-2.0 | 新；208 下载 | 实验 | RDFS 前向链并记录每步推导，输出可被 Lean 检查的证书 | 0.1、极小众；可借鉴其 provenance 设计 | 观察名单 |

**判断**

- **不要承诺 OWL 2 DL**。截至本次检索，没有发现生产级、许可证友好、经过公开 conformance 验证的 Rust OWL 2 DL reasoner；`rustdl` 是较活跃的尝试之一，但 0.4.x + LGPL 传递依赖不适合 Apache-2.0 产品。
- `reasonable` 是最实用的“买来即用”OWL 2 RL 物化器，但它自述只是 W3C 规则的**子集**，且没有证明树；它更适合做交叉验证/批量物化，而不是 heirloom 的解释层。
- v0.1 应把推理限制在 **RDFS + OWL 2 RL 的明确子集**，规则用 SPARQL CONSTRUCT 表达（规则即数据），每条推导落 PROV-O；这样规则可审计、可版本化、可解释，且不引入 LGPL/新引擎风险。W3C OWL 2 RL 规则表见 [OWL 2 Profiles §4.3](https://www.w3.org/TR/owl2-profiles/#Reasoning_in_OWL_2_RL_and_RDF_Graphs_using_Rules)。

### 2.5 Datalog / 前向链 / Rete

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `ascent` | 0.8.1（2026-08-29） | MIT | 中；597 stars；813k / 370k | 稳定 0.x | 宏编译成 Rust；半朴素、分层否定、聚合、lattice、`ascent_par!` 用 rayon 并行 | 无 provenance/证明树；无增量删除；规则编译期固定 | **首选（固定内置规则）** |
| `crepe` | 0.2.0（2025-12-14） | MIT OR Apache-2.0 | 中低；537 stars；851k / 90k；仓库 push 2025-12 | 稳定 0.x | 半朴素、分层否定、Rust 表达式；README 称 10^6 关系级传递闭包与编译型 Souffle 同量级 | `run(self)` 消费 runtime、从空 IDB 重算；无增量/证明树 | **备选** |
| `datafrog` | 2.0.1（2026-10-12） | Apache-2.0/MIT | 高；912 stars；5.2M / 507k | 生产级 2.0 | 极轻量、无 runtime、手动迭代；rustc/polonius 用过 | 规则用 Rust 手写；无 provenance；无聚合 | **备选/自定义引擎底座** |
| `differential-dataflow` | 0.26.0（2026-11-05） | MIT | 高；3k stars；571k / 135k | 稳定 0.x | 真正的增量：输入增删、迭代、arrangement；README 示例 10M 节点/50M 边首次约 15.5s、单次更新约 230µs | 学习/实现成本高；无 RDF/证明树；编译与运行时开销大 | **v0.3+ 的增量选项** |
| `timely` | 0.32.0（2026-11-05） | MIT | 高；724k / 148k | 稳定 0.x | 数据流运行时，differential 的底座 | 不是 Datalog 引擎 | 组件，不单独选 |

**判断**

- `ascent` 与 `crepe` 都是**编译期** Datalog：规则是 Rust 代码，新增/修改规则要重新编译。它们适合 heirloom 的**固定内置规则**（RDFS/OWL RL 子集、实体消歧规则），不适合用户自定义规则。用户规则应走 SPARQL CONSTRUCT（见 §4.4）。
- 证明树/增量 TMS 都不是库特性，需要自建；详见 §3.3。

### 2.6 属性图与图算法

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `petgraph` | 0.8.3（2025-09-30） | MIT OR Apache-2.0 | 中；仓库 push 11-14；616M / 147M | 生产级 0.8 | `Graph`/`StableGraph`/`GraphMap`/`MatrixGraph`；Dijkstra、MST、同构、SCC 等；可导出 DOT | 纯内存；索引失效规则；主干正在多 crate 重构 | **首选** |
| `graph`（neo4j-labs） | 0.3.2（2026-06-10） | MIT | 中；447 stars；125k / 19k；仓库 push 10-09 | 新兴 0.x | CSR 布局、rayon 并行构建；README 称面向十亿节点/边 | 不可变 CSR，改图要重建；无持久化；0.x | **备选（大规模只读分析）** |
| `rayon` | 1.12.0（2026-04-14） | MIT OR Apache-2.0 | 高；13.5k stars；669M / 170M | 生产级 1.x | 数据并行迭代/`par_iter`；与 ascent 的并行模式配合 | 不是图库；嵌套并行/async 混用需谨慎 | **首选并行底座** |

**判断**

- heirloom 的图是 RDF 图，不是属性图。`petgraph` 适合把查询结果/子图投影到内存做算法（中心性、路径、社区、影响传播）；`graph` 适合一次性构建 CSR 做十亿边级只读分析，但会引入第二套图表示，v0.1 不必。
- 不要为了图算法引入 Neo4j 风格属性图存储；RDF + 投影足够。

### 2.7 向量索引

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `hnsw_rs` | 0.3.5（2026-10-09） | MIT/Apache-2.0 | 高；257 stars；1.4M / 609k | 稳定 0.x | HNSW（Malkov & Yashunin）；多线程插入/查询、dump/reload、mmap 数据、过滤、多种距离 | 内存索引；更新/删除需重建或外层墓碑；无量化/压缩 | **首选（v0.1）** |
| `arroy` | 0.9.0（2026-10-22） | MIT | 高；LMDB 后端；545k / 78k | 稳定 0.x | Annoy 风格随机投影树 + LMDB；多进程共享、增量更新、低内存、原子多索引 | README 自述“generally slower due to log(n) lookups and non-aligned vectors”；高维召回需调参 | **备选（需要持久化/多进程）** |
| `usearch` | 2.26.6（2026-10-31） | Apache-2.0 | 高；4.4k stars；1.7M / 800k | 稳定 0.x | C++20 核心 + SIMD/量化；RAM/VRAM/NVMe；并发可变 | README 要求 **Rust 1.100+、CMake 3.28、C++20**；当前工具链 1.98.1 不能构建；FFI/构建复杂 | **不进 v0.1** |
| `lancedb` | 0.41.0（2026-10-29） | Apache-2.0 | 高；11.9k stars；2.4M / 1.45M | 稳定 0.x | Lance 列式、版本化、向量+全文+SQL；嵌入式/对象存储 | 依赖 DataFusion/Lance/AWS SDK 等一大棵树；异步；0.42 仍是 beta；对 v0.1 过重 | **v0.3+ 评估** |
| `qdrant-client` | 1.20.0（2026-10-22） | Apache-2.0 | 高；6.3M / 2.9M | 生产级 client | Qdrant 的 HTTP/gRPC 客户端 | 需要外部 Qdrant 服务，违背本地优先/单二进制 | **仅未来适配器** |
| `instant-distance` | 0.6.1（2023-06-26） | MIT OR Apache-2.0 | 停更 | 稳定但旧 | 不可变 HNSW，构建后只读 | 2023 后无 release；无更新/删除 | **淘汰** |
| `sqlite-vec` | 0.1.9（2026-05-18） | MIT/Apache-2.0 | 中高；4.1M / 1.7M | 实验 0.1 | SQLite 扩展，向量与业务数据同库 | 0.1.x；FFI 扩展；需要 SQLite 主存储才划算 | 观察名单 |

**判断**

- v0.1 的嵌入规模（个人/团队知识库，10^4–10^6 chunk）用 `hnsw_rs` 足够；索引可 dump/load，重建成本可接受。把“向量 + 元数据 + 索引版本”放 redb，索引文件按 generation 目录管理。
- 如果 v0.1 就要求“删除文档立即生效、不重建”，`arroy` 的 LMDB 增量模型更合适；如果要求“十亿向量/多租户”，再评估 LanceDB/Qdrant，而不是现在。
- `usearch` 的性能诱惑很大，但构建门槛与 MSRV 使其不适合作为 v0.1 的第一依赖；等其 Rust 绑定稳定后再做 benchmark 替换。

### 2.8 嵌入式存储 / 事务

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `redb` | 4.6.0（2026-11-13） | MIT OR Apache-2.0 | 高；4.9k stars；16.2M / 6.9M | 生产级 4.x | 纯 Rust、copy-on-write B+tree、ACID、MVCC、崩溃安全、savepoint、多进程多写者 | 无 SQL/全文；单写事务仍需应用层串行；4.x API | **首选（应用状态）** |
| `rusqlite` / SQLite | 0.42.0（2026-11-11） | MIT；SQLite public domain | 高；4.5k stars；155M / 61.8M | 生产级 | B-tree、WAL 读不阻塞写/写不阻塞读、但**同时只有一个 writer**；FTS5/JSON1/生态成熟 | C 依赖；大事务/WAL 调参；并发写弱 | **备选（需要 SQL/FTS）** |
| `sled` | 0.34.7（2024-10-11） | MIT OR Apache-2.0 | 停更；仓库 push 2026-04 但 README 明确 main 是大重写 | beta | 日志结构、无锁读、事务 | 两年无 release；README 与 main 不同步；生产风险高 | **淘汰** |
| `duckdb` | 1.20000.2（2026-11-13；crate 版本对应 DuckDB 1.2） | MIT | 高；987 stars；6.1M / 2.7M | 生产级 1.2 | 列式向量化 OLAP、ACID、单文件、Parquet/JSON 扩展 | 面向分析型长查询/批量写，不是高频事务；单写 | **仅分析/导出** |
| `rocksdb` | 0.25.0（2026-08-16） | Apache-2.0 | 高；2.2k stars；60M / 8M | 生产级 | LSM、高写吞吐、压缩、列族 | C++ 构建；调参复杂；Oxigraph 已封装，重复引入无收益 | **仅经 Oxigraph 使用** |
| `heed` / LMDB | 0.23.1（2026-10-26） | MIT；LMDB OpenLDAP | 高；920 stars；7.9M / 3.2M | 生产级 | 内存映射 B+tree、多进程、读极快、单写 | LMDB 单写；Rust 安全封装有 `unsafe` 边界；许可证需保留 notice | 备选（多进程 KV） |
| `fjall` | 3.1.13（2026-10-25） | MIT OR Apache-2.0 | 高；2.4k stars；2.2M / 795k | 新兴 3.x | 纯 Rust LSM、列族、可选事务、压缩 | 3.x 年轻；事务/一致性语义需实测 | 观察名单 |
| `libsql` | 0.10.0（2026-10-13） | MIT | 高；2.65M / 852k | 新兴 0.x | SQLite fork，嵌入式 + 远程 | 0.x；与 rusqlite 生态分叉 | 备选（需要 SQLite 兼容+远程） |

**判断**

- **Oxigraph 的 RocksDB 已经是 RDF 主存储**；再引入一个 RocksDB 只会增加构建/调参负担。应用状态（任务、文档、快照、索引元数据、决策记录）用 `redb`，保持纯 Rust 与简单事务模型。
- 如果 v0.1 需要全文检索（FTS5）或复杂 SQL 报表，`rusqlite` 比 redb 更合适；这是唯一值得为 SQL 付出的 C 依赖。SQLite WAL 的并发模型是“多读 + 单写”，与 Oxigraph 的单写模型一致，架构上不矛盾。[SQLite WAL](https://www.sqlite.org/wal.html)
- `duckdb` 只应出现在“导出 Parquet/Arrow 后做分析”路径，不应承担在线写入。

### 2.9 列式 / 进程间数据交换

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `arrow` + `arrow-ipc` | 60.1.0（2026-10-28） | Apache-2.0 | 高；3.7k stars；100M / 28M | 生产级 | 列式内存格式、零拷贝切片、IPC 文件/流；Python↔Rust 的 C Data Interface | 版本节奏快（约月度）；必须与 PyArrow/DataFusion 对齐 | **首选 IPC** |
| `parquet` | 60.1.0（2026-10-28） | Apache-2.0 | 高；94M / 25M | 生产级 | 列式持久化/导出、压缩、谓词下推 | 不适合当在线事务存储 | **首选导出/归档** |
| `arrow-flight` | 60.1.0（2026-10-28） | Apache-2.0 | 高；14M / 2.4M | 生产级 | 基于 gRPC 的 Arrow 数据流；跨进程/跨语言 | 需要 tonic/protobuf；进程内用不上 | **可选（gRPC 场景）** |
| `datafusion` | 55.2.0（2026-10-07） | Apache-2.0 | 高；9.6k stars；32M / 8M | 生产级 55.x | 向量化 SQL/DataFrame 引擎；Arrow 原生；可查 Parquet/CSV | 依赖树大、编译慢；不是图/SPARQL 引擎 | **可选（分析）** |

**判断**

- Python 与 Rust 之间优先用 **Arrow IPC / Arrow C Data Interface**，不要用 JSON 传 embedding/表格；`arrow-pyarrow` 就是做这件事的。[arrow-pyarrow](https://crates.io/crates/arrow-pyarrow)
- DataFusion 只在“需要对导出数据做 SQL 分析”时启用，做成可选 feature；不要把它塞进核心查询路径。
- Arrow Flight 只有在“Python worker 独立进程 + 需要 gRPC 流式传输”时才需要；进程内 PyO3 用 C Data Interface 更简单。

### 2.10 服务与 RPC

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `axum` | 0.8.9（2026-04-14） | MIT | 高；27.8k stars；593M / 165M | 生产级 0.8 | tokio/hyper；路由、提取器、中间件、WebSocket/SSE；REST 工作台 | 0.8 生态仍有小版本迁移；需要 tokio | **首选 REST/HTTP** |
| `rmcp` | 3.10.0（2026-11-12） | Apache-2.0 | 高；4.1k stars；49M / 29M | 生产级 3.x | 官方 MCP Rust SDK；stdio/HTTP transport；MCP 2026-07-28 | 3.x 有 breaking change；MCP 规范演进快 | **首选 MCP** |
| `tonic` | 0.14.9（2026-10-29） | MIT | 高；12.5k stars；475M / 115M | 生产级 0.14 | gRPC over HTTP/2、prost 代码生成、流式；Arrow Flight 底座 | 仓库已迁到 `grpc/grpc-rust`，master 在准备 breaking；protobuf 工具链 | **可选（gRPC/Flight）** |
| `jsonrpsee` | 0.26.1（2026-09-30） | MIT | 高；862 stars；27.7M / 4M | 生产级 0.26 | JSON-RPC 2.0 server/client、WS/HTTP | MCP 有自己的生命周期/能力协商，不要用 jsonrpsee 硬拼 MCP | **备选（通用 JSON-RPC）** |

**判断**

- v0.1 的 API 面：REST（axum）+ MCP（rmcp）+ CLI（clap）；除非 Python worker 需要 gRPC 流式，否则不需要 tonic。
- `jsonrpsee` 只在“确实要一个通用 JSON-RPC API”时使用；MCP 用官方 `rmcp`，不要自己实现协议。

### 2.11 Python 互操作

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `pyo3` | 0.30.1（2026-10-14） | MIT OR Apache-2.0 | 高；16.3k stars；306M / 75M | 生产级 0.30 | CPython 3.9+、PyPy/GraalPy；`abi3` 稳定 ABI；free-threaded 3.13t wheel 支持 | GIL/FFI 边界；abi3 限制部分 API；Python 版本矩阵 | **首选（SDK/可选嵌入）** |
| `maturin` | 1.16.0（2026-10-29） | MIT OR Apache-2.0 | 高；5.9k stars；1.17M / 125k | 生产级 1.x | 构建/发布 PyO3 wheel；PyPI 上传 | 需要 Python 构建环境；CI 矩阵 | **首选构建** |
| `arrow-pyarrow` | 60.1.0（2026-10-28） | Apache-2.0 | 高；8.4M / 2.25M | 生产级 | Arrow C Data Interface + PyO3，零拷贝传 Arrow 数组 | 必须与 PyArrow/Arrow 版本匹配 | **首选零拷贝** |
| 子进程 + Arrow IPC | — | — | — | — | Python worker 独立进程，stdin/stdout 或本地 HTTP 传 Arrow IPC | 需要进程管理/错误传播 | **首选运行时隔离** |

**判断**

- **不要把任意 Python AI 代码嵌入 Rust 进程**。PyO3 适合两种用途：(a) 给 Python 用户一个 `heirloom` 原生扩展 SDK；(b) 可选的嵌入式轻量调用。生产运行时用独立 Python worker，崩溃隔离、依赖隔离、可单独升级 torch/transformers。
- PyO3 0.30 支持 `abi3`，建议 `abi3-py310` 出单 wheel 覆盖 3.10+；free-threaded wheel 单独构建，不要默认开。[PyO3 README](https://github.com/PyO3/pyo3)

### 2.12 可观测性

| 候选 | 版本（发布） | 许可证 | 活跃度 | 成熟度 | 性能/契合 | 风险 | 结论 |
|---|---|---|---|---|---|---|---|
| `tracing` | 0.1.44（2025-12-18） | MIT | 高；7k stars；1.05B / 274M | 生产级 0.1 | 结构化 span/event、字段、异步上下文；库/应用通用 | 0.1 长期稳定但字段语法有坑 | **首选** |
| `tracing-subscriber` | 0.3.23（2026-03-13） | MIT | 高；750M / 196M | 生产级 0.3 | EnvFilter、JSON、fmt、组合 layer | 默认输出需配置 | **首选** |
| `opentelemetry` | 0.33.1（2026-10-09） | Apache-2.0 | 高；2.8k stars；332M / 89M | Beta（traces） | OTel API/SDK；traces Beta、metrics/logs stable | pre-1.0 破坏性变更；traces 尚未 stable | **可选 OTLP** |
| `opentelemetry-otlp` | 0.33.1（2026-10-09） | Apache-2.0 | 高；196M / 58M | Beta/Stable 混合 | OTLP HTTP/gRPC 导出 | 版本需与 `opentelemetry` 对齐 | **可选** |
| `tracing-opentelemetry` | 0.34.0（2026-09-23） | MIT | 高；252M / 65M | 生产级 | 把 tracing span 桥到 OTel | **版本号不与 otel 同步**：0.34 对应 otel 0.33 | **可选桥接** |

**判断**

- 默认只依赖 `tracing` + `tracing-subscriber`；OTLP 作为 `otel` feature，避免本地 CLI 被 OTel 依赖污染。
- 版本锁定：`tracing-opentelemetry 0.34` ↔ `opentelemetry 0.33`；升级时一起升，别只升一个。[兼容说明](https://github.com/tokio-rs/tracing-opentelemetry#compatibility-with-opentelemetry-crates)

## 3. 特别问题

### 3.1 Oxigraph 能否同时承担 RDF 存储 + SPARQL 查询 + 事务？

**结论：能，作为 v0.1 的 RDF 主存储/查询/更新层；但不能把它当通用事务数据库。** 需要接受四个边界：单写实例、事务全量缓存、RocksDB/C++ 构建、SPARQL 求值未优化。

#### 嵌入模式

| 模式 | API | 存储 | 适用 |
|---|---|---|---|
| 内存 | `Store::new()` | 无 RocksDB；进程内 | 测试、临时图、WASM（关闭 `rocksdb` feature） |
| 磁盘读写 | `Store::open(path)` | RocksDB 目录 | v0.1 主存储 |
| 磁盘只读 | `Store::open_read_only(path)` | RocksDB 只读 | 只读副本；**有写进程时打开是 UB** |
| 带选项 | `Store::open_with_options(path, StoreOptions)` | RocksDB | 调 `max_open_files`/fd 预留 |

`rocksdb` 是默认 feature；关闭后只剩内存后端，这是 WASM 构建的默认行为。[lib/oxigraph Cargo.toml](https://github.com/oxigraph/oxigraph/blob/main/lib/oxigraph/Cargo.toml)

```rust
use oxigraph::model::*;
use oxigraph::sparql::{QueryResults, SparqlEvaluator};
use oxigraph::store::Store;

// 磁盘模式；目录不存在会创建
let store = Store::open("data/oxigraph")?;

// 写事务：repeatable read + 原子提交
let mut tx = store.start_transaction()?;
tx.insert(Quad::new(
    NamedNode::new("https://example.com/s")?,
    NamedNode::new("https://example.com/p")?,
    NamedNode::new("https://example.com/o")?,
    GraphName::DefaultGraph,
));
tx.commit()?;

// 只读快照：不持锁，writer 不被阻塞
let snapshot = store.snapshot();

// SPARQL 1.1 Query
if let QueryResults::Solutions(mut solutions) = SparqlEvaluator::new()
    .parse_query("SELECT ?s WHERE { ?s ?p ?o }")?
    .on_store(&store)
    .execute()?
{
    while let Some(solution) = solutions.next() {
        let _ = solution?;
    }
}
```

> 代码为示意；API 以 [Store docs](https://docs.rs/oxigraph/latest/oxigraph/store/struct.Store.html) 为准。`Store::open` 在非 wasm + `rocksdb` feature 下可用。

#### 并发模型

- `Store` 是 `Clone`，源码里有编译期断言 `is_send_sync::<Store>()`、`is_send_sync::<Transaction<'_>>()`、`is_send_sync::<BulkLoader<'_>>()`，所以可以跨线程共享。[store.rs](https://github.com/oxigraph/oxigraph/blob/main/lib/oxigraph/src/store.rs)
- **每个读方法各自拿一个快照**；`Store::snapshot()` 返回一个钉住某一时刻的只读视图，**不持锁**，writer 不会被阻塞。[Store docs](https://docs.rs/oxigraph/latest/oxigraph/store/struct.Store.html)
- **写事务持锁**，因此 writer 之间串行；事务是 **repeatable read** 隔离级别、原子提交，读操作在事务期间看到一致状态。[Store docs](https://docs.rs/oxigraph/latest/oxigraph/store/struct.Store.html)
- **同一数据库目录只能有一个读写 `Store`**；可以有额外只读实例，但“另一个进程正在写时以只读打开”被文档明确称为 **undefined behavior**。[Store docs](https://docs.rs/oxigraph/latest/oxigraph/store/struct.Store.html)
- 因此并发模型是：**单进程多线程 + 单写者 + 多读者**。如果 heirloom 需要多进程写入，必须让一个进程做 writer，其他进程通过 IPC/HTTP 访问；不要让两个进程各开一个 `Store::open`。

#### 持久化格式

- 底层是 **RocksDB**（LSM-tree），数据目录包含 RocksDB 的 SST/WAL/MANIFEST 等文件；不是单文件格式，不能像 SQLite 那样直接拷贝一个文件就完事。[Oxigraph README](https://github.com/oxigraph/oxigraph#readme)
- `Store::backup(target_dir)` 生成可独立打开的备份；同一文件系统内可能用硬链接，成本低。备份只支持 `Store::open` 创建的磁盘库，不支持 `Store::new()` 的内存库。[Store docs](https://docs.rs/oxigraph/latest/oxigraph/store/struct.Store.html)
- 跨系统迁移用 `dump_to_writer(RdfFormat::NQuads, ...)`；不要依赖 RocksDB 文件格式。
- 大批量导入用 `bulk_loader()`；`load_from_reader`/`load_from_slice` 是原子的但“慢且吃内存”。[Store docs](https://docs.rs/oxigraph/latest/oxigraph/store/struct.Store.html)
- Oxigraph 自己维护 `oxrocksdb-sys`（基于 rust-rocksdb 绑定的 fork），构建时需要 C++ 工具链；这是“单二进制”分发的主要构建复杂度来源。[oxrocksdb-sys](https://github.com/oxigraph/oxigraph/tree/main/oxrocksdb-sys)

#### 能力与限制

| 维度 | 结论 |
|---|---|
| SPARQL 1.1 Query | 支持；包括 property path、聚合、子查询等 |
| SPARQL 1.1 Update | 支持；每次 update 原子提交 |
| Federated Query (`SERVICE`) | README 声称支持；需要 `http-client` feature，默认不开 |
| RDF 格式 | Turtle/TriG/N-Triples/N-Quads/RDF/XML/JSON-LD；JSON-LD 通过 `oxjsonld` |
| RDF 1.2 | 主 README 称“preliminary support”，在 `rdf-12` feature 后；v0.1 不应依赖 |
| RDF 规范化 | 支持 RDF Dataset Canonicalization（RDFC-1.0），可用于快照/内容哈希 |
| 事务 API | 事务是 **quad 级**（insert/remove/commit），没有在事务内执行 SPARQL Query/Update 的接口；SPARQL Update 走 `Store::update`，本身原子。不要把 `Store::update` 与已打开的 `Transaction` 混用（没有组合 API，且可能因写锁阻塞） |
| 事务内存 | **事务把完整变更集放内存**；文档明确“不要用它加载数千万三元组” |
| 查询优化 | README 自述“SPARQL query evaluation has not been optimized yet”；有 `sparopt` 优化器和 `explain()`/统计，但不能假设它比专用引擎快 |
| 查询取消/超时 | 文档未提供内建 query timeout；需要在服务层用迭代器 drop/任务取消/超时包装 |
| 多进程写 | 不支持；一个读写实例 |
| 推理/校验/向量 | 都不提供；需外接 |
| 成熟度 | 仓库 wiki 自述是 “hobby project”（目标 v0.4），但项目活跃、发布规律、采用面广；按“稳定 0.x”对待，不按“生产级数据库”对待 |

**适用判断**

- **适合**：v0.1 的 RDF 数据集、SPARQL 查询/更新、命名图、快照、RDF 导出、内容哈希；单机/单进程多线程服务。
- **不适合**：高并发多进程写入、需要跨 RDF 与非 RDF 数据的单一 ACID 事务、需要毫秒级复杂 SPARQL 分析查询、需要内建全文/向量/推理。
- **建议**：把 Oxigraph 当“RDF 子系统”，而不是“heirloom 数据库”；应用状态、任务、索引元数据放 redb；跨库一致性用 outbox/幂等批次，不追求分布式事务。

### 3.2 rudof 的 SHACL/ShEx 覆盖度如何？是否适合 v0.1 校验器？

**结论：`shacl` crate 适合 v0.1 的 SHACL Core 校验；ShEx 有实现但不建议进 v0.1。** 证据强度：SHACL 高（测试树 + CI），ShEx 中（官方套件 README 记录 1062/82/22/0，但本次未独立执行）。

#### SHACL 覆盖度

- `shacl` 0.3.39 是从 rudof 拆出的独立 crate，README 描述两种引擎：
  - **Native**：内存图，约束用 Rust 逻辑求值；开 `sparql` feature 后，内联 `sh:sparql` 走 SPARQL 执行路径。
  - **Sparql**：面向远端 SPARQL endpoint，把每个约束翻译成 `SELECT`/`ASK`；需要 `sparql` feature。
- 约束模块覆盖 SHACL Core 的主要族（源码目录）：
  - `cardinality`（minCount/maxCount）
  - `logical`（and/or/not/xone）
  - `node_expr_based`（node expression / `sh:expression`）
  - `non_shape`（deactivated）
  - `other`（closed/hasValue/in）
  - `property_pair`（equals/disjoint/lessThan/lessThanOrEquals/subsetOf）
  - `shape_based`（node/qualifiedValueShape）
  - `string_based`（languageIn/minLength/maxLength/pattern/uniqueLang）
  - `value`（class/datatype/nodeKind）
  - `value_range`（min/max inclusive/exclusive）
  - `sparql`（SHACL-SPARQL 约束）
- 测试证据：测试树声明 **118** 个 data-shapes 用例，其中 **4** 个 SHACL-SPARQL 用例标 `#[ignore]`；`validate_runner` 对每个用例同时跑 `Native` 和 `Sparql` 并比较完整报告（忽略 `sh:detail`）。[测试代码](https://github.com/rudof-project/rudof/blob/master/shacl/tests/common/runner/validate.rs)
- CI 在递归 checkout submodule 后执行 `cargo test --workspace`，所以这个测试 target 是持续验证的一部分。[CI](https://github.com/rudof-project/rudof/blob/master/.github/workflows/ci.yml)
- 未覆盖/弱项：
  - **SHACL-SPARQL**：4 个用例被 `#[ignore]`（不等于全部未实现，但说明有已知缺口）；Native 模式下 `SERVICE` 还需要额外 `http-client` feature。
  - **SHACL-JS**：未发现 README/源码宣称支持；v0.1 不应依赖。
  - **SHACL 1.2**：仓库有 `shacl12` 测试目录，属于进行中；v0.1 按 SHACL 1.0 设计。
  - **报告细节**：测试比较时去掉 `sh:detail`，说明嵌套 detail 不是其强项；如果 heirloom 要把报告作为一等产物，需要自己验证。
- 资源模型：Native 模式需要把数据图放进内存；10^6 级三元组通常可行（取决于 shape 复杂度），10^8 级需要 Sparql 模式/分区，必须实测。

**是否适合 v0.1？** 适合，但按“可替换的校验适配器”接入：
- 只依赖 `shacl`，`default-features = false` 避免默认 `sparql` 把 Oxigraph/tokio/reqwest 拉进来；v0.1 只做 SHACL Core。
- 通过 `rudof_rdf` 的图类型转换 Oxigraph 的 quads（批量校验，不共享存储）；校验结果落 RDF（`sh:ValidationReport`），保留 shape/constraint 的 IRI，便于前端解释。
- 固定 `0.3.39`，CI 里跑 W3C 套件 + 自己的 shapes；升级时对比报告差异。
- 如果 v0.1 后需要 SHACL-SPARQL/远端 endpoint，再开 `sparql` feature；此时要接受依赖膨胀和 4 个 ignored 用例。

#### ShEx 覆盖度

- rudof 的 ShEx 栈包括 `shex_ast`（ShExJ AST + ShExC 解析）、`shex_validation`（校验算法）、`rbe`（Regular Bag Expressions）、`shex_testsuite`（官方测试套件 harness），仓库内 vendored `shexTest`。[shex_validation](https://github.com/rudof-project/rudof/tree/master/shex_validation)
- README 明确给出运行 ShEx 测试套件的命令：
  `cargo run -p shex_testsuite -- -m shex_testsuite/shexTest/validation/manifest.jsonld`
- **项目 README 记录**的默认运行结果是：`Passed: 1062, Failed: 82, Skipped: 22, Not implemented: 0`（ShExC；ShExJ 为 1062/82）；这是项目自述，**本次没有独立执行**。82 个失败意味着不能宣称“ShEx 2.1 全通过”，需要在你关心的 schema 特性上复测。
- 结论：ShEx 覆盖度看起来较高（AST、ShExC/ShExJ、校验、RBE、官方套件），但不是“全通过”；heirloom v0.1 的标准清单只要求 SHACL（Map #1 写的是 SHACL），ShEx 属于额外兼容面。**不建议把 ShEx 放进 v0.1 验收标准**；保留 `rudof_lib`/`shex_validation` 适配点即可。

#### 集成风险

1. **`shacl` 默认 feature = `sparql`**：会启用 `rudof_rdf/sparql`，进而拉 `oxigraph`、`tokio`、`reqwest`、`spargebra`、`sparesults`。如果只要 SHACL Core，务必 `default-features = false`。
2. **feature 统一 / RDF 1.2**：rudof 的 workspace 给 `oxrdf`/`oxrdfio`/`oxttl` 开了 `rdf-12`；通过 `shacl` 间接引入时，Cargo 的 additive features 可能把你自己的 Oxigraph 也编译进 RDF 1.2 分支。需要 `cargo tree -e features` 检查，并避免对 `Term` 做穷尽匹配。
3. **版本对齐**：rudof 0.3.39 依赖 `oxigraph 0.5.9`、`oxrdf 0.3.3`；与 Oxigraph 0.5.13 / oxrdf 0.3.5 是 caret 兼容，但需要 `cargo tree -d` 确认没有重复版本。
4. **0.x API**：`shacl` 0.3.x，rudof 仓库 170 个 open issues；升级会破坏 API；用适配器隔离，不要让校验类型泄漏到领域模型。
5. **性能**：Native 模式全图入内存；Sparql 模式把约束翻译成查询，可能产生大量查询。v0.1 先在快照上批量校验，不要在校验期间接受写入。

### 3.3 ascent/crepe 能否表达可解释的 Datalog 推导并输出证明树？增量真值维护是否可行？

**结论：能“手工表达”证明树，但库本身不提供；增量真值维护没有现成支持，v0.1 应批量重算，增量是后续工程。** 另外，二者都是编译期规则，不适合用户自定义规则。

#### 它们提供什么

| 能力 | ascent | crepe |
|---|---|---|
| 半朴素求值 | 是 | 是 |
| 分层否定 | 是 | 是 |
| 聚合 | 是（`sum/min/max/count/mean`，可自定义） | 未在 README 中作为特性强调（可用 Rust 表达式手写） |
| lattice | 是（`lattice` 关键字，`Lattice` trait） | 未在 README 中作为特性强调 |
| 并行 | `ascent_par!` / `ascent_run_par!`，rayon | 未强调 |
| 规则形态 | 编译期宏，规则是 Rust 代码 | 编译期宏，规则是 Rust 代码 |
| 证明树 | **无内建** | **无内建** |
| 增量删除/TMS | **无** | **无**；`run(self)` 消费 runtime，从空 IDB 重算 |

来源：[ascent README](https://github.com/s-arash/ascent) · [crepe README](https://github.com/ekzhang/crepe) · [crepe `make_run` 源码](https://github.com/ekzhang/crepe/blob/main/src/lib.rs)

#### 如何编码证明树

核心思路：**不要试图让引擎“返回证明”，而是把证明作为关系的一部分推导出来。**

```rust
// 示意，非编译验证；列类型需满足 Clone + Eq + Hash
ascent! {
    relation triple(Node, Node, Node);
    relation derived(Node, Node, Node, u64 /*fact_id*/, u64 /*rule_id*/, u32 /*depth*/);
    relation premise(u64 /*fact_id*/, u64 /*premise_id*/);

    // 规则示例：rdfs:subClassOf 传递
    derived(s, rdfs::SUB_CLASS_OF, o, fact_id(s, rdfs::SUB_CLASS_OF, o), RULE_SUBCLASS_TRANS, d + 1) <--
        derived(s, rdfs::SUB_CLASS_OF, m, _, _, d),
        derived(m, rdfs::SUB_CLASS_OF, o, _, _, _);

    premise(fact_id(s, rdfs::SUB_CLASS_OF, o), fact_id(s, rdfs::SUB_CLASS_OF, m)) <--
        derived(s, rdfs::SUB_CLASS_OF, m, _, _, _),
        derived(m, rdfs::SUB_CLASS_OF, o, _, _, _);
}
```

要点：

1. **fact_id**：用规范化的三元组哈希（如 BLAKE3/xxHash）或递增 ID；必须稳定、可复现。不要用 `DefaultHasher`（随机种子）。
2. **rule_id**：每条规则一个稳定 IRI/整数；规则集版本化，证明里记录版本。
3. **premise**：只记录**一条 canonical 推导**（第一条/最短的），否则所有支持的组合会爆炸。需要“所有解释”时，另建 `support` 关系并限制 fan-out。
4. **depth / rank**：用 `u32` 或 lattice `Dual<u32>` 取最短推导；这能避免传递闭包中的环导致无限证明。
5. **环**：传递闭包/等价类天然有环；证明树要按 depth/rank 选well-founded 的路径，并在展示层标记“循环依赖”。
6. **否定/聚合**：分层否定和聚合的 provenance 语义复杂（支持计数、反事实）；v0.1 只对**正 Datalog 片段**生成证明树，否定/聚合只记录“规则应用”而不展开完整证明。
7. **输出**：把 `derived`/`premise` 落成 RDF，用 PROV-O 词汇（Activity = 规则应用，Entity = 事实，`prov:used` = premise，`prov:wasGeneratedBy` = 规则），前端/API 再组装成树。PROV-O 是 W3C 标准，比自定义 JSON 更可审计。[PROV-O](https://www.w3.org/TR/prov-o/)

#### 增量真值维护（TMS）可行性

| 方案 | 做法 | 复杂度 | 适用 |
|---|---|---|---|
| **批量重算** | 每次变更后从输入关系重跑固定点，diff 新旧 IDB，写新 provenance | 低 | **v0.1 推荐**；规则少、图 10^5–10^6 时通常可接受（需实测） |
| **DRed / 支持计数** | 每个事实维护支持计数；删除时递减，归零则删除并递归重推导 | 中 | 规则固定、需要删除/更新的 v0.2 |
| **differential-dataflow** | 输入 collection 增删，`iterate` 求固定点；输出 delta | 高 | 大规模、频繁增量、可接受工程投入的 v0.3+ |
| **按规则/命名图作用域重算** | 每条规则/每个输入批次一个命名图；删除时只重算受影响子图 | 中 | 与 Oxigraph 命名图模型契合，但需要依赖分析 |

- `ascent`：没有“撤回事实”的 API；变更后需要让受影响的关系重新求值/重建 IDB，库不会自动维护 provenance。是否能在已有关系上继续 `run()` 取决于生成的代码，需在 spike 中验证；即使可以，也不支持删除。
- `crepe`：`run(self)` 消费 runtime，内部从空 IDB 开始，把 `@input` 关系装进去再跑；没有增量复用接口。每次变更都要新建 runtime 全量重算。[crepe `make_run`](https://github.com/ekzhang/crepe/blob/main/src/lib.rs)
- `datafrog`：手动 `Iteration` + `variable`，天然适合自己加支持计数/DRed；但没有声明式规则，维护成本高。
- `differential-dataflow`：真正支持增删和迭代，但要把 Datalog 规则、证明项、RDF 编码都映射到 collection/arrangement；这不是“接一个库”，而是一个子系统。README 的 10M 节点/50M 边例子（特定机器）首次约 15.5s、单次更新约 230µs，说明增量收益大，但首次构建和工程复杂度也大。[differential README](https://github.com/TimelyDataflow/differential-dataflow)

**建议**

- v0.1：**批量重算 + canonical provenance**；把 `fact_id/rule_id/premise/depth` 持久化，API 返回证明 DAG（不是无限树）。
- 用户规则：**SPARQL CONSTRUCT/UPDATE**，因为规则是数据、可版本化、可在 Oxigraph 上执行；`ascent`/`crepe` 只用于内置热点规则（如果 profiling 证明必要）。
- 不要把“可解释”实现成“每个事实保存所有推导”；存储会爆炸，而且大多数场景只需要一条可复现的 canonical 证明。

### 3.4 许可证是否都兼容 Apache 2.0 分发？

**结论：推荐栈的直接依赖全部兼容；需要主动避开 3 个坑：`rio`（GPL-3.0）、`horned-owl`（LGPL-3.0）以及传递依赖它的 `rustdl`。** 以下是直接依赖的许可证矩阵（不构成法律意见）。

| 类别 | 候选 | 许可证 | Apache-2.0 分发 | 备注 |
|---|---|---|---|---|
| RDF | `oxrdf`/`oxrdfio`/`oxttl`/`oxjsonld` | MIT OR Apache-2.0 | 可 | 选 Apache 分支 |
| RDF | `sophia` | Apache-2.0 OR CECILL-B | 可 | 选 Apache 分支；CECILL-B 是 BSD 类 |
| RDF | `rio_api`/`rio_turtle`/`rio_xml` | Apache-2.0 | 可（但停更） | 不建议 |
| RDF | `rio`（crate 名） | **GPL-3.0** | **不可（按 Apache-2.0）** | 不是 RDF Rio；是 io_uring 绑定 |
| SPARQL | `oxigraph`/`spareval`/`spargebra`/`sparesults` | MIT OR Apache-2.0 | 可 | 选 Apache |
| SPARQL | `sophia_sparql` | Apache-2.0/CECILL-B | 可 | 实验 |
| SHACL | `shacl`/`rudof_lib`/`shacl_validation` | MIT OR Apache-2.0 | 可 | 选 Apache |
| ShEx | `shex_validation`/`shex_ast` | MIT OR Apache-2.0 | 可 | 可选 |
| Datalog | `ascent` | MIT | 可 | 保留版权/许可文本 |
| Datalog | `crepe` | MIT OR Apache-2.0 | 可 | 选 Apache |
| Datalog | `datafrog` | Apache-2.0/MIT | 可 | 选 Apache |
| Datalog | `differential-dataflow`/`timely` | MIT | 可 | 保留文本 |
| 图 | `petgraph`/`rayon` | MIT OR Apache-2.0 | 可 | 选 Apache |
| 图 | `graph` | MIT | 可 | 保留文本 |
| 向量 | `hnsw_rs` | MIT/Apache-2.0 | 可 | 选 Apache |
| 向量 | `arroy` | MIT | 可 | 保留文本 |
| 向量 | `usearch` | Apache-2.0 | 可 | 构建风险，不是许可证风险 |
| 向量 | `lancedb` | Apache-2.0 | 可 | 依赖树大 |
| 向量 | `qdrant-client` | Apache-2.0 | 可 | 外部服务 |
| 存储 | `redb` | MIT OR Apache-2.0 | 可 | 选 Apache |
| 存储 | `rusqlite` | MIT；SQLite public domain | 可 | 保留 notice |
| 存储 | `duckdb` | MIT | 可 | DuckDB 本身 MIT |
| 存储 | `rocksdb`/`oxrocksdb-sys` | Apache-2.0；`oxrocksdb-sys` 是 **GPL-2.0-only OR Apache-2.0** | 可（选 Apache） | 保留 RocksDB NOTICE；建议法务确认 |
| 存储 | `heed` | MIT；LMDB OpenLDAP | 可 | 保留 LMDB notice |
| 存储 | `sled` | MIT OR Apache-2.0 | 可 | 停更风险，非许可证 |
| 存储 | `fjall` | MIT OR Apache-2.0 | 可 | 选 Apache |
| 列式 | `arrow`/`arrow-flight`/`parquet`/`datafusion` | Apache-2.0 | 可 | 保留 ASF NOTICE |
| 服务 | `axum`/`tonic`/`jsonrpsee` | MIT | 可 | 保留文本 |
| MCP | `rmcp` | Apache-2.0 | 可 | 官方 SDK |
| Python | `pyo3`/`maturin` | MIT OR Apache-2.0 | 可 | 选 Apache |
| Python | `arrow-pyarrow` | Apache-2.0 | 可 | 与 Arrow 同版本 |
| 观测 | `tracing`/`tracing-subscriber`/`tracing-opentelemetry` | MIT | 可 | 保留文本 |
| 观测 | `opentelemetry`/`opentelemetry-otlp` | Apache-2.0 | 可 | 保留 NOTICE |
| OWL | `reasonable` | BSD-3-Clause | 可 | 保留版权/免责声明 |
| OWL | `horned-owl` | **LGPL-3.0** | **高风险/不建议** | Rust 静态链接需提供可重链接对象；对单二进制不友好 |
| OWL | `rustdl`/`owl-dl-reasoner` | Apache-2.0 OR MIT（顶层） | **实际受 LGPL 约束** | 硬依赖 `horned-owl`；组合分发按 LGPL 处理 |
| OWL | `owl` | Apache-2.0 | 可（但停更） | 无维护 |
| OWL | `purrdf-entail` | MIT OR Apache-2.0 OR MulanPSL-2.0 | 可 | 选 MIT/Apache；4.0 RC |

**解释**

- Apache-2.0 项目可以包含 MIT、BSD-2/3、ISC、Zlib、CC0、Apache-2.0 的代码，前提是保留版权/许可/NOTICE。MIT 与 Apache-2.0 兼容；BSD-3 也兼容。
- **GPL-2.0-only** 与 Apache-2.0 不兼容；不要把 GPL-2.0-only 代码直接并入 Apache-2.0 二进制。`oxrocksdb-sys` 提供 `OR Apache-2.0` 选项，所以可选 Apache；但需确认其 vendored RocksDB 的 NOTICE/第三方代码。
- **GPL-3.0** 与 Apache-2.0 是单向兼容：Apache-2.0 代码可以进入 GPL-3.0 作品，但 Apache-2.0 作品不能直接包含 GPL-3.0 代码并继续按 Apache-2.0 分发。`rio` 是 GPL-3.0，不可用。
- **LGPL-3.0** 允许更宽松的链接，但静态链接的 Rust 二进制通常需要提供目标文件/可重链接方式，并允许用户替换库；对“单二进制、Apache-2.0”产品是实质性负担。`horned-owl` 是 LGPL-3.0，`rustdl` 顶层虽是 Apache/MIT，但硬依赖它；因此二者都不进推荐栈。
- `sophia` 的 CECILL-B 是 BSD 类许可证；双许可下选 Apache-2.0 即可。
- `reasonable` 是 BSD-3-Clause；BSD-3 与 Apache-2.0 兼容，保留版权/免责声明即可。
- `oxrocksdb-sys` 的双许可表达是 `GPL-2.0-only OR Apache-2.0`；这意味着你可以按 Apache-2.0 使用，但需要保留其 NOTICE，并确认没有额外 GPL-only 文件。建议让法务在发布前审一次。[oxrocksdb-sys Cargo.toml](https://github.com/oxigraph/oxigraph/blob/main/oxrocksdb-sys/Cargo.toml)
- **不要只信 crates.io 的 license 字段**：它可能把双许可写成 `MIT/Apache-2.0` 或 `Apache-2.0 / CECILL-B`；以仓库 `LICENSE*` 与 `Cargo.toml` 为准。发布前跑 `cargo-deny`（licenses + bans + sources）和 `cargo-about` 生成第三方通知。

## 4. 推荐的 v0.1 栈

### 4.1 依赖草图

```toml
# Cargo.toml（workspace 根，示意）
[workspace.dependencies]
# RDF / SPARQL：单一 Oxigraph 栈，避免第二套模型
oxigraph = { version = "0.5", default-features = true } # 需要 SERVICE 时加 http-client-rustls-native
# 解析/序列化优先用 oxigraph::io / oxigraph::model 的 re-export；
# 若需要独立使用，再加 oxrdfio = "0.2"、oxttl = "0.2"、oxjsonld = "0.2"

# 校验：只取 SHACL Core，避免默认 feature 拉 oxigraph/tokio/reqwest
shacl = { version = "0.3", default-features = false }
# 需要 SHACL-SPARQL 时：shacl = { version = "0.3", features = ["sparql"] }

# 应用状态
redb = "4"

# 向量
hnsw_rs = "0.3"

# 图算法 / 并行
petgraph = "0.8"
rayon = "1"

# 列式交换
arrow = "60"           # 默认含 ipc
parquet = { version = "60", optional = true }

# API / MCP
axum = "0.8"
rmcp = { version = "3", features = ["server"] }

# Python SDK（独立 cdylib crate 使用；不要塞进主二进制）
# extension-module 只放在 heirloom-py 的 Cargo.toml，不要放 workspace 依赖
pyo3 = { version = "0.30", features = ["abi3-py310"] }
arrow-pyarrow = "60"

# 观测
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter", "json"] }

# 可选
reasonable = { version = "0.4", optional = true }      # OWL 2 RL 交叉验证
datafusion = { version = "55", optional = true }        # 分析型 SQL
opentelemetry = { version = "0.33", optional = true }
opentelemetry-otlp = { version = "0.33", optional = true }
tracing-opentelemetry = { version = "0.34", optional = true }
```

> 版本是 2026-11-15 的 minor 线，不是 lockfile。落地时用 `cargo tree -d` 检查重复的 `oxrdf`/`oxigraph`，并用 `cargo deny check` 做许可证/来源审计。

### 4.2 进程与 crate 边界

```
heirloom (Rust workspace)
├── heirloom-core        领域类型、ID、错误、配置；不依赖存储
├── heirloom-rdf         Oxigraph Store 封装、批量导入、快照、导出
├── heirloom-reason      规则注册表、SPARQL CONSTRUCT 执行、PROV-O provenance
├── heirloom-validate    shacl 适配器、shape 管理、ValidationReport
├── heirloom-vector      hnsw_rs 索引、embedding 元数据、generation 管理
├── heirloom-store       redb 表、任务/决策/索引元数据、outbox
├── heirloom-api         axum REST/SSE + rmcp MCP
├── heirloom-cli         clap CLI（init/ingest/query/reason/validate/serve）
└── heirloom-py          PyO3 cdylib（Python SDK；可选）

heirloom-py-worker (Python，独立进程)
├── 解析/切分/embedding/LLM 抽取
└── 通过 Arrow IPC（stdin/stdout 或本地 HTTP）与 Rust 交换 RecordBatch
```

**为什么这样切**：

- `heirloom-rdf` 是唯一持有 `Store` 的 crate；其他模块通过接口拿快照/查询结果，避免 `Store` 被到处 clone 后忘记单写约束。
- `heirloom-reason` 不直接依赖 `ascent`；先做 SPARQL 规则层，`ascent` 只作为内部优化实现，方便替换。
- `heirloom-validate` 隔离 `shacl` 的 0.x API 和 feature 污染；领域层只看到 `ValidationReport`。
- `heirloom-py` 只暴露 SDK（`open_store`、`query`、`validate`、`explain`），不承载 AI 依赖。

### 4.3 存储布局与一致性

```
~/.heirloom/
├── oxigraph/                 # RocksDB：RDF 数据集
├── state.redb                # 应用状态：documents/jobs/decisions/index metadata
├── vectors/
│   ├── gen-0001/             # hnsw_rs dump（按 embedding model/维度）
│   └── gen-0002/
├── blobs/                    # 内容寻址的原始文件
└── snapshots/
    └── 2026-11-15T.../       # manifest + 可选 Oxigraph backup
```

- **没有跨 Oxigraph/redb 的 ACID 事务**。写入流程：
  1. 在 redb 写 `batch` 记录（`pending`），包含批次 ID、输入哈希、目标图/规则版本；
  2. 以幂等方式写 Oxigraph（每个批次一个命名图，或带 `batch_id` 的 provenance）；
  3. redb 标记 `committed`；启动时扫描 `pending` 做对账。
- **快照**：`snapshot manifest` 记录 RDFC 内容哈希、redb savepoint/批次水位、vector generation；恢复时按顺序校验。Oxigraph backup 用于 RDF 主体，redb savepoint 用于状态，向量索引按 generation 不可变。
- **不要**把 embedding 向量存进 Oxigraph 字面量（base64/JSON）；放 redb 的 blob 表 + hnsw 索引，RDF 只存 `chunk_id`/`model_id`/`vector_ref`。

### 4.4 规则与 provenance

- **规则即数据**：内置 RDFS/OWL 2 RL 子集规则以 SPARQL `CONSTRUCT` 存在 `urn:heirloom:rules` 命名图中，带版本号/描述/作者；用户规则同构，但需要语法/分层/安全校验。
- 执行：`SparqlEvaluator` 对 `CONSTRUCT` 求值；结果写入 `urn:heirloom:inference:<batch>` 命名图；同时写 PROV-O：
  - `prov:Activity` = 一次规则应用（`prov:used` premise facts + 规则 Plan）；
  - `prov:Entity` = 推导出的事实；
  - `prov:wasGeneratedBy` 连接事实与 activity；
  - `prov:startedAtTime`/`prov:endedAtTime` 记录批次时间；
  - `prov:Plan` = 规则/规则集版本（作为 Activity 的 `prov:used`）。
- **证明树 API**：从目标事实沿 `prov:wasGeneratedBy`/`prov:used` 反向走，按 depth 选最短路径；返回 DAG + 规则标签 + 输入事实；环标记为 `cycle`。
- **终止/安全**：规则按 stratum 排序；禁止负环/不安全变量；设置最大迭代次数、最大推导事实数、最大单规则时间；失败返回部分结果 + 原因。
- **OWL 2 DL 不做**：本研究明确 heirloom v0.1 只支持 RDFS + OWL 2 RL 子集，不要在市场材料里写 OWL 2 DL。若未来必须 DL，先做 sidecar 评估（Java reasoner 或新 Rust 引擎），不要现在承诺。

### 4.5 校验

- Shape 以 RDF 存 `urn:heirloom:shapes`；每个 shape 有版本/严重级别/消息。
- 校验在**快照**上跑：`store.snapshot()` → 转 `rudof_rdf` 图 → `shacl` Native；结果 `sh:ValidationReport` 写回 `urn:heirloom:validation:<batch>`。
- 大图策略：按 target class 分区、分批校验；Native 内存峰值设上限；超限降级到 `Sparql` 模式（如果启用了 `sparql` feature）或返回“未校验”而不是静默通过。
- 报告要保留 `sh:focusNode`、`sh:resultPath`、`sh:sourceShape`、`sh:sourceConstraintComponent`、`sh:resultSeverity`、`sh:resultMessage`；前端可点回事实/规则。
- v0.1 不接 ShEx；`shex_validation` 只作为未来适配点。

### 4.6 向量检索

- `embedding model` 维度/版本写入 redb；每个 `(model_id, dim)` 一个 hnsw 索引目录。
- 写入：先写 redb `chunks`（文本、hash、metadata），再批量插入 hnsw；索引 generation 不可变，增量写入写新 generation 或使用可更新索引（后续换 arroy）。
- 查询：hnsw 返回 `chunk_id + distance` → redb/Oxigraph hydrate → 过滤（权限、时间、图范围）→ 返回带 provenance 的上下文。
- 删除：v0.1 用 tombstone + 周期性重建；如果删除频繁，切 `arroy`（LMDB 增量）。
- 不要用 `qdrant-client` 作为 v0.1 默认；它需要外部服务，破坏本地优先。

### 4.7 API / MCP / CLI

- REST：`axum`，`/v1/ingest`、`/v1/query`、`/v1/validate`、`/v1/explain`、`/v1/snapshot`；SSE 推送 job 进度。
- MCP：`rmcp`，stdio（本地 agent）+ streamable HTTP（工作台）；工具名/参数与 REST 共享 `heirloom-core` 的服务层，不要复制业务逻辑。
- CLI：`clap`（未在 ticket 中研究，但为 Rust 标准选择）；`heirloom serve`/`ingest`/`query`/`validate`/`snapshot`。
- 认证：本地 token/文件权限；v0.1 不做多租户。

### 4.8 可观测性

- `tracing` span 命名：`ingest.batch`、`parse.file`、`extract.python`、`graph.merge`、`reason.rule`、`validate.shape`、`vector.search`、`api.request`；字段用 `batch_id`、`doc_id`、`rule_id`、`shape_id`、`duration_ms`、`count`。
- 默认 `tracing-subscriber` JSON 到 stderr/文件；`RUST_LOG` 过滤；CLI 人类可读。
- `otel` feature 开 OTLP：traces Beta，metrics/logs stable；生产默认关，配置后开。
- 不记录原文/embedding/PII；只记录 ID、长度、哈希。

### 4.9 明确不选

| 不选 | 原因 |
|---|---|
| `rio`（任何 RDF 分支） | 已归档；`rio` crate 名是 GPL-3.0 陷阱 |
| `sophia` 作为第二套模型 | 与 Oxigraph 转换成本高；只作备选 |
| `shacl_validation` 0.2 | 旧引擎；新项目用 `shacl` |
| `ShEx` 进 v0.1 | 标准范围外，复杂度高 |
| `sled` | beta 重写、两年无 release |
| 直接 `rocksdb` | Oxigraph 已封装；重复引入无收益 |
| `usearch` | README 要求 Rust 1.100/CMake/C++20；当前工具链不可构建 |
| `lancedb` | 依赖树过重、异步、0.42 beta |
| `qdrant-client` | 需要外部服务，非本地优先 |
| `horned-owl` / `rustdl` | LGPL-3.0 传递依赖，不适合 Apache-2.0 单二进制 |
| OWL 2 DL | 截至本次检索，未发现生产级、许可证友好的 Rust 实现 |
| DataFusion 默认 | 分析型引擎，不是核心查询路径 |
| PyO3 嵌入 AI 运行时 | GIL/依赖/崩溃隔离问题；用独立 worker |
| 真·单二进制（含 Python） | 与 Python 依赖现实冲突；改为 Rust 单二进制 + sidecar |

### 4.10 对 Map 的决策建议

建议在 `docs/adr/` 落四条 ADR（研究完成后再写，不在本 ticket 直接创建）：

1. **ADR-0001：Oxigraph 作为 RDF 存储/SPARQL/事务层**，记录单写、事务内存、RocksDB 构建、SPARQL 性能 caveat。
2. **ADR-0002：RDF 与业务状态分库**（Oxigraph + redb），记录无跨库事务、outbox/幂等批次、快照协议。
3. **ADR-0003：RDFS + OWL 2 RL 子集 + PROV-O 证明**，记录不支持 OWL DL、canonical proof、批量 TMS。
4. **ADR-0004：Python 边界**，记录独立 worker + Arrow IPC、PyO3 仅 SDK、单二进制收窄。

## 5. 验证与基准计划

### 5.1 本次已核实 / 未核实

| 项目 | 状态 |
|---|---|
| 版本、发布日期、下载量、许可证字段 | 已从 crates.io API 核实（2026-11-15） |
| 仓库活跃度、归档状态、open issues | 已从 GitHub API 核实 |
| Oxigraph API/事务/并发/持久化描述 | 已从 docs.rs、README、源码核实 |
| `shacl` 测试树/CI/约束模块 | 已从源码/CI 核实；**未亲自跑套件** |
| ShEx 测试套件 | 已确认存在；README 记录 1062 passed / 82 failed / 22 skipped / 0 not implemented；**未独立执行** |
| OWL 规则覆盖 | 已从 reasonable 官方规则表核实（35/9/18） |
| 许可证兼容性 | 直接依赖已核实；**传递依赖未审计** |
| 性能数字 | 仅引用项目自述；**未跑独立 benchmark** |
| 编译可行性 | 仅确认本机 rustc 1.98.1 与部分 MSRV；**未编译整套栈** |

### 5.2 落地前必须跑的基准

1. **RDF 解析/加载**：N-Triples/Turtle/JSON-LD，10^5/10^6/10^7 三元组；记录吞吐、峰值内存、导入时间；对比 Oxigraph 单条插入 vs 事务批量 vs `bulk_loader`。
2. **SPARQL**：BSBM（Oxigraph 仓库自带 `sparqloscope-bsbm-5000.csv` 和 store bench）+ 自己的 20 条真实查询；冷/热缓存、并发 1/4/16；记录 p50/p95、QPS、内存。
3. **SHACL**：W3C 套件 + 10 个真实 shape；10^4/10^5/10^6 三元组；Native vs Sparql；记录校验时间、内存、报告大小；确认 4 个 ignored 用例是否影响你的 shape。
4. **Datalog/证明**：传递闭包、same-generation、RDFS 规则集；10^4/10^5/10^6 事实；记录固定点时间、证明表大小、证明查询延迟；对比 `ascent` vs 纯 SPARQL CONSTRUCT。
5. **向量**：10^4/10^5/10^6 向量，768/1536 维；recall@10、QPS、构建时间、索引大小；`hnsw_rs` vs `arroy`（如果持久化需求明确）。
6. **存储**：redb vs SQLite 的写入/读取/范围扫描/事务/崩溃恢复；确认 redb 多进程写行为在你的部署模型下可用。
7. **端到端**：1k 文档 ingest → parse → extract → graph build → validate → reason → query/explain；记录 p95 与内存；这才是发布门槛。

### 5.3 验收建议

- 先跑基线，再定阈值；不要在没数据前写“10 万 QPS”这类目标。
- 每个基准固定：数据版本、embedding model、硬件、OS、Rust 版本、feature flags、Oxigraph 版本。
- 证明/校验结果要可重复：固定 hash seed（不要 `DefaultHasher`）、记录规则版本、shape 版本、索引 generation。

## 6. 风险与开放问题

| 风险 | 影响 | 缓解 |
|---|---|---|
| Oxigraph SPARQL 求值未优化 | 复杂查询 p95 可能不达标 | 先跑 BSBM/真实查询；必要时把热点查询物化/缓存，或评估 `spareval` + 自定义索引 |
| Oxigraph 单写 + 事务全量内存 | 高并发写入/大批量导入受限 | 单 writer actor；bulk_loader；批次大小上限；导入与查询分离 |
| Oxigraph RocksDB C++ 构建 | 单二进制构建/CI 复杂 | CI 预编译；发布二进制；`rocksdb-pkg-config` 可选 |
| `shacl` 0.x / 内存模式 | API 破坏、大图 OOM | 适配器隔离；快照/分批；固定版本；超限降级 |
| `shacl` feature 统一/RDF 1.2 | 意外启用 rdf-12、重复 Oxigraph | `default-features=false`；`cargo tree -e features`；CI feature 矩阵 |
| ShEx 未验证 | 不能承诺 ShEx 兼容 | 不进 v0.1 验收；需要时跑官方套件 |
| OWL 2 DL 缺失 | 市场/客户期望落空 | 明确只支持 RDFS + OWL 2 RL 子集；文档写清 |
| Datalog 无证明树/TMS | 解释功能需自建，增量成本高 | canonical provenance；批量重算；DRed/differential 后置 |
| 向量索引重建 | 删除/模型升级成本 | generation 目录；tombstone；arroy 备选 |
| 跨库一致性 | RDF/状态不一致 | outbox + 幂等 + 启动对账；不做分布式事务 |
| 许可证传递依赖 | 发布合规风险 | `cargo-deny`；NOTICE；避免 LGPL/GPL；法务审 |
| Python sidecar | 部署多一个进程 | 进程管理/健康检查；Arrow IPC；打包为可选组件 |
| RDF 1.2 draft | 规范变动 | v0.1 用 RDF 1.1 + PROV-O；`rdf-12` 仅实验 |
| `usearch`/LanceDB 诱惑 | 过早引入重依赖 | 以 benchmark 为准；v0.1 不引入 |

**开放问题（留给 Map/后续 ticket）**

1. heirloom 的 RDF 规模目标是多少（10^6 / 10^7 / 10^8）？这决定 Oxigraph 是否够、SHACL 是否需要 Sparql 模式、向量是否需要 arroy/LanceDB。
2. 是否需要多进程写入（CLI + server + Python worker 同时写）？如果需要，Oxigraph 的单写实例需要设计成单一 writer 服务。
3. 规则语言是否对用户开放？开放到什么程度（SPARQL CONSTRUCT 子集 / 受控 Datalog）？这决定 `ascent` 是否只是内部优化。
4. 证明的粒度：一条 canonical 证明，还是所有支持？前者省存储，后者更可审计但会爆炸。
5. 是否需要全文检索？如果需要，SQLite FTS5 vs Tantivy（未研究）会改变存储选择。
6. ShEx 是否有外部兼容需求？没有就从 v0.1 移除。
7. Python 层是否需要 GPU/torch？如果需要，sidecar 是唯一现实方案；如果只是轻量抽取，PyO3 嵌入也可。
8. 是否接受“Rust 单二进制 + Python sidecar”作为部署形态？这需要 Map 明确收窄“单二进制”的定义。

## 7. 主要来源

**规范**

- [SPARQL 1.1 Overview](https://www.w3.org/TR/sparql11-overview/)
- [SHACL](https://www.w3.org/TR/shacl/) · [W3C SHACL test suite](https://w3c.github.io/data-shapes/data-shapes-test-suite/)
- [ShEx semantics](https://shex.io/shex-semantics/)
- [OWL 2 Profiles — RL rules](https://www.w3.org/TR/owl2-profiles/#Reasoning_in_OWL_2_RL_and_RDF_Graphs_using_Rules)
- [PROV-O](https://www.w3.org/TR/prov-o/)
- [RDF 1.1 Concepts](https://www.w3.org/TR/rdf11-concepts/) · [RDF Dataset Canonicalization](https://www.w3.org/TR/rdf-canon/)
- [SQLite WAL](https://www.sqlite.org/wal.html) · [DuckDB: Why DuckDB](https://duckdb.org/why_duckdb) · [RocksDB](https://rocksdb.org/)

**项目文档/源码**

- Oxigraph: [README](https://github.com/oxigraph/oxigraph#readme) · [Store docs](https://docs.rs/oxigraph/latest/oxigraph/store/struct.Store.html) · [store.rs](https://github.com/oxigraph/oxigraph/blob/main/lib/oxigraph/src/store.rs) · [Architecture wiki](https://github.com/oxigraph/oxigraph/wiki/Architecture) · [oxrocksdb-sys](https://github.com/oxigraph/oxigraph/tree/main/oxrocksdb-sys)
- oxrdf: [docs](https://docs.rs/oxrdf/latest/oxrdf/) · [oxrdfio](https://crates.io/crates/oxrdfio) · [oxjsonld](https://github.com/oxigraph/oxigraph/tree/main/lib/oxjsonld)
- Sophia: [README](https://github.com/pchampin/sophia_rs) · [crates.io](https://crates.io/crates/sophia)
- Rio: [rio_api](https://crates.io/crates/rio_api) · [rio_turtle](https://crates.io/crates/rio_turtle) · [archived repo](https://github.com/oxigraph/rio) · [rio (GPL)](https://crates.io/crates/rio)
- rudof / SHACL: [repo](https://github.com/rudof-project/rudof) · [shacl crate](https://crates.io/crates/shacl) · [shacl docs](https://docs.rs/shacl/latest/shacl/) · [shacl tests](https://github.com/rudof-project/rudof/tree/master/shacl/tests) · [constraints](https://github.com/rudof-project/rudof/tree/master/shacl/src/validator/constraints)
- ShEx: [shex_validation](https://crates.io/crates/shex_validation) · [shex_testsuite](https://github.com/rudof-project/rudof/tree/master/shex_testsuite)
- Ascent: [README](https://github.com/s-arash/ascent) · [crates.io](https://crates.io/crates/ascent)
- Crepe: [README](https://github.com/ekzhang/crepe) · [lib.rs](https://github.com/ekzhang/crepe/blob/main/src/lib.rs)
- Datafrog: [README](https://github.com/rust-lang/datafrog) · [crates.io](https://crates.io/crates/datafrog)
- Differential Dataflow: [README](https://github.com/TimelyDataflow/differential-dataflow) · [crates.io](https://crates.io/crates/differential-dataflow)
- Petgraph: [README](https://github.com/petgraph/petgraph) · [graph (neo4j-labs)](https://github.com/neo4j-labs/graph) · [rayon](https://github.com/rayon-rs/rayon)
- Vector: [hnsw_rs](https://github.com/jean-pierreBoth/hnswlib-rs) · [arroy](https://github.com/meilisearch/arroy) · [usearch Rust README](https://github.com/unum-cloud/usearch/blob/main/rust/README.md) · [lancedb](https://github.com/lancedb/lancedb) · [qdrant-client](https://github.com/qdrant/rust-client)
- Storage: [redb](https://github.com/cberner/redb) · [sled](https://github.com/spacejam/sled) · [rusqlite](https://github.com/rusqlite/rusqlite) · [duckdb-rs](https://github.com/duckdb/duckdb-rs) · [rust-rocksdb](https://github.com/rust-rocksdb/rust-rocksdb) · [heed](https://github.com/meilisearch/heed) · [fjall](https://github.com/fjall-rs/fjall)
- Columnar: [arrow-rs](https://github.com/apache/arrow-rs) · [arrow-flight](https://crates.io/crates/arrow-flight) · [datafusion](https://github.com/apache/datafusion)
- Service: [axum](https://github.com/tokio-rs/axum) · [tonic](https://github.com/grpc/grpc-rust) · [jsonrpsee](https://github.com/paritytech/jsonrpsee) · [rmcp](https://github.com/modelcontextprotocol/rust-sdk)
- Python: [PyO3](https://github.com/PyO3/pyo3) · [maturin](https://github.com/PyO3/maturin) · [arrow-pyarrow](https://crates.io/crates/arrow-pyarrow)
- Observability: [tracing](https://github.com/tokio-rs/tracing) · [opentelemetry-rust](https://github.com/open-telemetry/opentelemetry-rust) · [tracing-opentelemetry](https://github.com/tokio-rs/tracing-opentelemetry)
- OWL: [reasonable](https://github.com/gtfierro/reasonable) · [rules table](https://github.com/gtfierro/reasonable/blob/master/docs/owl-rules/index.rst) · [rustdl](https://github.com/MaastrichtU-IDS/rustdl) · [horned-owl](https://github.com/phillord/horned-owl)

**数据接口**

- [crates.io API](https://crates.io/api/v1/crates/oxigraph)（版本/下载/许可证字段；2026-11-15 抓取）
- [GitHub REST API](https://docs.github.com/en/rest)（stars/push/license/archived；2026-11-15 抓取）
