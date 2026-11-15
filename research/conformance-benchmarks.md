# 标准一致性测试与基准计划（草案）

> 服务于 wayfinder ticket [#5](https://github.com/0xnicholas/heirloom/issues/5)，并作为 [#17「v0.1 验收、基准与演示」](https://github.com/0xnicholas/heirloom/issues/17) 的输入。
> 核对快照：**2026-11-15**。文中所有 commit、测试数量、许可证与 URL 都以该日拉取到的内容为准；正式实施时应把 commit 和文件校验和写进 `suites.lock.toml`。
>
> **一句话结论**：RDF/Turtle/JSON-LD/SPARQL/SHACL 的官方套件足以支撑 v0.1 的“标准兼容”声明，Rust 生态已有可复用的 runner，但都需要 vendor/适配，不能直接 `cargo add`；OWL 2 RL/QL、PROV-CONSTRAINTS、SKOS 没有现成 Rust runner，必须自建 harness；Semantica 的 118k/0.004 ms 数字不可从仓库复现，只能当作待验证假设，不能作为验收基线。

---

## 1. 范围与假设

本文只覆盖 **v0.1 需要的标准一致性测试与性能基准**，不覆盖：

- 托管多租户、企业连接器、Windows 打包；
- 完整 OWL 2 DL 推理（只讨论 RL/QL 的可用子集）；
- RDF 1.2 / SPARQL 1.2 / SHACL 1.2 的正式兼容声明（这些在 2026-11-15 仍是 Candidate Recommendation Draft / Working Draft）。

采用的假设（若 #17 推翻，需要同步改门槛）：

1. v0.1 声明兼容 **RDF 1.1、Turtle 1.1、N-Triples 1.1、JSON-LD 1.1、SPARQL 1.1 Query/Update、SHACL 1.0、OWL 2 RL、PROV-O、SKOS**；不声明 RDF 1.2 / SPARQL 1.2 / SHACL 1.2。
2. Rust 语义核心使用 **Oxigraph** 承担 RDF 存储与 SPARQL 查询/更新，或至少使用其 `oxrdf`/`oxttl`/`spargebra`/`spareval` 组件；否则 runner 仍可复用，但需要写适配层。
3. v0.1 暴露 **SPARQL 1.1 Protocol** 或至少一个兼容的 HTTP 查询端点；若最终不暴露，协议/GSP 门槛标记为 N/A，不能声称协议兼容。
4. 一致性测试与性能门槛 **不依赖 LLM**。LLM 抽取的质量另立 eval，不进入 conformance gate；CI 用固定抽取 fixture。
5. 性能数字只在 **固定参考机 + release 构建 + 单线程（除非注明）** 下比较；开发机数字只作 smoke。

---

## 2. 套件总览

### 2.1 RDF / JSON-LD / 规范化

| 套件 | 版本 | manifest 条目 | 官方性 | 来源 | 许可证 | 现成 Rust runner |
|---|---|---:|---|---|---|---|
| RDF 1.1 Turtle | 2014 Rec | 313（145 eval / 74 pos syntax / 94 neg syntax） | 官方 W3C | [rdf-tests/rdf/rdf11/rdf-turtle](https://github.com/w3c/rdf-tests/tree/7b5afb91857a9c282c03d2e45d3f76485797c901/rdf/rdf11/rdf-turtle) | W3C Test Suite License + W3C 3-Clause BSD | Oxigraph `oxigraph-testsuite` |
| RDF 1.1 N-Triples | 2014 Rec | 70（41 pos / 29 neg） | 官方 W3C | [rdf-n-triples](https://github.com/w3c/rdf-tests/tree/7b5afb91857a9c282c03d2e45d3f76485797c901/rdf/rdf11/rdf-n-triples) | 同上 | Oxigraph |
| RDF 1.1 N-Quads | 2014 Rec | 87（53 pos / 34 neg） | 官方 W3C | [rdf-n-quads](https://github.com/w3c/rdf-tests/tree/7b5afb91857a9c282c03d2e45d3f76485797c901/rdf/rdf11/rdf-n-quads) | 同上 | Oxigraph |
| RDF 1.1 TriG | 2014 Rec | 357（143 eval / 99 pos / 115 neg） | 官方 W3C | [rdf-trig](https://github.com/w3c/rdf-tests/tree/7b5afb91857a9c282c03d2e45d3f76485797c901/rdf/rdf11/rdf-trig) | 同上 | Oxigraph |
| RDF 1.1 RDF/XML | 2014 Rec | 166（126 eval / 40 neg） | 官方 W3C | [rdf-xml](https://github.com/w3c/rdf-tests/tree/7b5afb91857a9c282c03d2e45d3f76485797c901/rdf/rdf11/rdf-xml) | 同上 | Oxigraph |
| RDF 1.1 Semantics | 2014 Rec | 62（48 + 14 AZ；25 pos entailment / 23 neg / 11 AZ pos / 3 AZ neg） | 官方 W3C | [rdf-mt](https://github.com/w3c/rdf-tests/tree/7b5afb91857a9c282c03d2e45d3f76485797c901/rdf/rdf11/rdf-mt) | 同上 | **无**（Oxigraph 不跑） |
| RDF Dataset Canonicalization 1.0 | 2024 Rec | 86（64 eval / 21 map / 1 negative） | 官方 W3C | [rdf-canon/tests](https://github.com/w3c/rdf-canon/tree/5ffaaa13c0e19a2a2f9e60d0ecd9efaf3edab633/tests) | W3C Software and Document License | Oxigraph `testsuite/tests/canonicalization.rs` |
| JSON-LD 1.1 | 2020 Rec | 1,279（toRdf 467 / expand 386 / compact 246 / flatten 58 / fromRdf 54 / HTML 50 / remote-doc 18） | 官方 W3C | [json-ld-api/tests](https://github.com/w3c/json-ld-api/tree/55514738d854e56bb8bad4c53cda2f0e23be4a50/tests) | W3C Test Suite License + W3C 3-Clause BSD | Oxigraph 只跑 toRdf；`json-ld` crate 跑 expand/compact/flatten/toRdf |

RDF 1.1 五个具体语法套件合计 **993** 条；加 RDF Semantics 62 条，共 **1,055** 条。JSON-LD 1.1 全量 **1,279** 条；RDFC 1.0 **86** 条。

说明：

- RDF/XML 的 manifest entries 是 166，但文件里被标注为 `rdft:TestXMLEval` / `TestXMLNegativeSyntax` 的资源有 173 个；以 manifest entries 为准计算通过率，避免把未列入 `mf:entries` 的辅助资源算成测试。
- RDF 1.2 套件（Turtle 109 / TriG 64 / N-Triples 76 / N-Quads 73 / RDF/XML 31 / Semantics 30，合计 383 条）已经存在，但 2026-11-15 的 RDF 1.2 仍是 Candidate Recommendation Draft，**不进入 v0.1 gate**；可作为 RDF 1.2 readiness 的 nightly 预览。
- RDFC 1.0 是 2024 年 Recommendation，适合作为“确定性导出/快照哈希”的正式依据。

### 2.2 SPARQL 1.1

| 套件 | manifest 条目 | 细项 | 官方性 | 来源 | 许可证 |
|---|---:|---|---|---|---|
| Query | 353 | 247 eval / 65 pos syntax / 41 neg syntax | 官方 W3C | [manifest-sparql11-query.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/manifest-sparql11-query.ttl) | W3C Test Suite License + BSD |
| Update | 157 | 94 eval / 42 pos update / 13 neg update / 8 neg syntax | 官方 W3C | [manifest-sparql11-update.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/manifest-sparql11-update.ttl) | 同上 |
| Federated Query | 10 | 7 eval / 3 pos syntax | 官方 W3C | [manifest-sparql11-fed.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/manifest-sparql11-fed.ttl) | 同上 |
| Query Results (JSON/CSV/TSV) | 11 | 7 eval / 4 CSV | 官方 W3C | [manifest-sparql11-results.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/manifest-sparql11-results.ttl) | 同上 |
| Entailment Regimes | 70 | 70 eval（RDF/RDFS/OWL 等 regime） | 官方 W3C | [entailment/manifest.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/entailment/manifest.ttl) | 同上 |
| Protocol | 34 | HTTP 请求序列 | 官方 W3C | [protocol/manifest.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/protocol/manifest.ttl) | 同上 |
| Graph Store Protocol | 13 | 4 direct / 9 indirect | 官方 W3C | [graph-store-protocol/manifest.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/graph-store-protocol/manifest.ttl) | 同上 |
| Service Description | 3 | 3 | 官方 W3C | [service-description/manifest.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/service-description/manifest.ttl) | 同上 |
| **合计** | **651** | 331 QueryEvaluation / 94 UpdateEvaluation / 68 pos syntax / 49 neg syntax / 42 pos update / 13 neg update / 34 protocol / 13 GSP / 4 CSV / 3 service description | | [manifest-all.ttl](https://github.com/w3c/rdf-tests/blob/7b5afb91857a9c282c03d2e45d3f76485797c901/sparql/sparql11/manifest-all.ttl) | |

另有 `http-rdf-update/manifest.ttl` 的 18 条 legacy Graph Store HTTP 测试，不在 `manifest-all.ttl` 的 651 条里；v0.1 只跑 direct/indirect 13 条即可，legacy 18 条作为兼容性加分项。

按 README 的官方定义，`QueryEvaluationTest` 的通过标准是结果集/图同构；`ORDER BY` 只有在结果用 `rs:index` 显式编码时才要求顺序；`REDUCED` 的 `mf:LaxCardinality` 允许每个解出现 1 到期望次数。更新测试比较执行前/后的 Graph Store 状态。协议测试要替换 `ht:connectionAuthority` 和 `/sparql/` 前缀，按 `ht:requests` 顺序发请求，并核对 `mf:expectedStatus`、`mf:expectedFormat`、`mf:expectedBoolean` 与 RDF 图同构。

### 2.3 SHACL / OWL / PROV / SKOS

| 套件 | 版本 | 规模 | 官方性 | 来源 | 许可证 | 现成 Rust runner |
|---|---|---|---:|---|---|---|
| SHACL 1.0 Test Suite | 2017 Rec | 121 条（120 approved / 1 proposed；Core 103 + SHACL-SPARQL 18） | 官方 W3C | [data-shapes-test-suite](https://github.com/w3c/data-shapes/tree/752769264137d192b3d603075162819466c46690/data-shapes-test-suite) | W3C Software and Document License | rudof `shacl` 集成测试 |
| OWL 2 RL | 2012 Rec | 91（70 approved / 4 proposed / 17 无状态；76 consistency / 30 pos entailment / 14 inconsistency / 6 neg entailment，类型可重叠） | 官方 W3C（归档） | [profile-RL.rdf](https://www.w3.org/2009/11/owl-test/profile-RL.rdf) | W3C 托管，归档未单独标注许可证 | **无** |
| OWL 2 QL | 2012 Rec | 65（45 approved / 3 proposed） | 官方 W3C（归档） | [profile-QL.rdf](https://www.w3.org/2009/11/owl-test/profile-QL.rdf) | 同上 | **无** |
| OWL 2 EL | 2012 Rec | 87 | 官方 W3C（归档） | [profile-EL.rdf](https://www.w3.org/2009/11/owl-test/profile-EL.rdf) | 同上 | **无**（v0.1 不需要） |
| OWL 2 全量 | 2012 Rec | 489（355 approved / 86 proposed） | 官方 W3C（归档） | [all.rdf](https://www.w3.org/2009/11/owl-test/all.rdf) | 同上 | **无** |
| PROV-CONSTRAINTS | 2013 Rec | 约 294 个 case ID（222 PASS / 72 FAIL）；238 个 RDF/PROV-O 文件；56 条 constraint | 官方 W3C | [testcases](https://github.com/w3c/prov/tree/aa82bd71b6bb1f7b735bf3f7f5b948fae87764f0/testcases)、[process.html](https://github.com/w3c/prov/blob/aa82bd71b6bb1f7b735bf3f7f5b948fae87764f0/testcases/process.html) | 仓库无 LICENSE 文件；W3C 托管 | **无** |
| SKOS Reference | 2009 Rec | 62 个编号条件（S1–S62；核心 SKOS 46 个，SKOS-XL 16 个；其中核心一致性条件约 10 条） | 官方规范，**无测试套件** | [SKOS Reference](https://www.w3.org/TR/skos-reference/) | W3C Document License | **无** |
| SHACL 1.2 | 2026 WD | 预览套件（core/node-expr/SPARQL） | W3C Working Draft | [data-shapes/shacl12-test-suite](https://github.com/w3c/data-shapes/tree/752769264137d192b3d603075162819466c46690/shacl12-test-suite) | W3C Software and Document License | rudof 已接线（部分 ignore） |

OWL 2 细节：

- `profile-RL.rdf` 是 W3C OWL WG 导出文件，2012 年归档；官方入口 `https://www.w3.org/2009/owl-test-cases` 现在返回一个跳转说明，真正归档在 `https://www.w3.org/2009/11/owl-test/`。
- RL 的 91 条里 70 条 `Approved`、4 条 `Proposed`、17 条没有 status；做 gate 时只要求 70 条 Approved，Proposed/无状态单独报告。
- 同一测试可以同时是 `ProfileIdentificationTest` + `ConsistencyTest` + `PositiveEntailmentTest`，所以类型计数加起来大于 91。
- 若 #9 决定 v0.1 只做 RL，QL 的 65 条（45 approved）只作为“未来 QL 能力”的预览；QL 不是靠物化实现，需要 query rewriting，不能拿 RL 的 harness 冒充。

PROV 细节：

- 294 个 case ID 按文件名区分 `-PASS-` / `-FAIL-`；每个 case 有 `.ttl`（PROV-O）、`.provn`、`.provx` 三种表示，但少数 invalid 构造没有 RDF 表示，所以 RDF 文件只有 238 个。
- 官方 `process.html` 要求：validator 对每个 case 判定 valid/invalid，必须与文件名里的 PASS/FAIL 一致；输出 pass list / fail list，并映射到 56 条 constraint（文件名后缀 `c30` 等）。
- 官方 spec 的 constraint 编号到 **Constraint 56**，不是常被引用的 29 条。

SKOS 细节：

- SKOS Reference §1.8 明确说 **“This specification does not define a formal notion of conformance.”** 所以没有 W3C SKOS 测试套件，也不应声称“SKOS conformant”。
- 可执行的做法是把 S1–S62 中真正的一致性约束（核心 S9–S14、S27、S36–S37、S46，SKOS-XL 另加 S48/S58）做成正/负 fixture，用 SHACL 或 OWL RL consistency 检查；其余编号是词汇定义/公理，不是 integrity condition。
- 社区补充：`skohub-io/skohub-shapes` 的 `skos.shacl.ttl`（Apache-2.0）覆盖了大部分 SKOS 结构约束，可作为起点和交叉验证，但它不是 W3C 官方测试。

---

## 3. 获取、运行、通过标准

### 3.1 RDF 1.1 / RDFC

**获取（固定 commit）**

```bash
git clone --depth 1 https://github.com/w3c/rdf-tests.git vendor/rdf-tests
git -C vendor/rdf-tests checkout 7b5afb91857a9c282c03d2e45d3f76485797c901

git clone --depth 1 https://github.com/w3c/rdf-canon.git vendor/rdf-canon
git -C vendor/rdf-canon checkout 5ffaaa13c0e19a2a2f9e60d0ecd9efaf3edab633
```

**运行（参考 Oxigraph 的 runner）**

```bash
# 在 Oxigraph 源码树中；heirloom 需要 vendor 或改造成自己的 harness
cargo test -p oxigraph-testsuite --test parser
cargo test -p oxigraph-testsuite --test canonicalization
```

`oxigraph-testsuite` 的 `parser.rs` 明确覆盖 N-Triples、N-Quads、Turtle、TriG、RDF/XML；`canonicalization.rs` 覆盖 RDFC-1.0。它直接 `check_testsuite("https://w3c.github.io/...")` 远程拉 manifest，**不是 hermetic**；CI 必须改成读本地 `vendor/` 并把 URL 映射到本地路径。

**通过标准**

- Positive syntax：解析成功，无错误。
- Negative syntax：解析必须报错；不报错即失败。
- Evaluation：解析出的 RDF dataset 与期望 dataset **同构**（RDF dataset isomorphism）；建议先做 RDFC-1.0 canonicalization，再逐字节比较 N-Quads，避免 bnode 顺序问题。
- RDF Semantics：按 RDF 1.1 Semantics 的 entailment/consistency 判定；v0.1 若不做 RDFS/OWL entailment，则整组标记 N/A，不能悄悄跳过。
- RDFC：canonical N-Quads 逐字节一致；negative test 必须报错。

### 3.2 JSON-LD 1.1

**获取**

```bash
# json-ld crate 把官方套件作为 submodule
git clone --depth 1 https://github.com/timothee-haudebourg/json-ld.git vendor/json-ld
git -C vendor/json-ld checkout 3f5b63ebd9ba15dba4dc9f8b6ae21d58c301cd21
git -C vendor/json-ld submodule update --init --recursive
```

**运行**

```bash
cargo test -p json-ld --test expand
cargo test -p json-ld --test compact
cargo test -p json-ld --test flatten
cargo test -p json-ld --test to_rdf
```

`json-ld` 的 `testing/derive` 会从 manifest 生成每条测试；它覆盖 expand/compact/flatten/toRdf，不覆盖 fromRdf、HTML、remote-doc。Oxigraph 只跑 toRdf（467 条），并显式 ignore 了 expandContext、produceGeneralizedRdf、rdfDirection、1.0 specVersion 等 19 条左右。

**通过标准**

- `toRdf`：输出 dataset 与期望 dataset 同构。
- `expand` / `flatten` / `compact`：按 JSON-LD Test Suite README 的对象比较算法；对象成员顺序无关，数组一般无序（`@list` 例外）；`ordered` 模式下才要求数组顺序。
- `fromRdf`：dataset 同构（若 v0.1 需要 JSON-LD 导入）。
- Negative evaluation：必须返回 manifest 指定的错误码（如 `invalid @id value`），仅“报错”不够。
- HTML/remote-doc：如果 v0.1 只处理本地 JSON-LD 文档，可标记 N/A；不要声称“完整 JSON-LD processor”。

### 3.3 SPARQL 1.1 Query / Update

**获取**：同上 `vendor/rdf-tests`。

**运行（Oxigraph 参考）**

```bash
cargo test -p oxigraph-testsuite --test sparql
```

`testsuite/tests/sparql.rs` 覆盖：

- SPARQL 1.0 syntax / evaluation；
- SPARQL 1.1 query evaluation；
- SPARQL 1.1 federation；
- SPARQL 1.1 update；
- JSON/TSV 结果格式；
- 可选 SPARQL 1.2（feature flag）。

**通过标准**

- Syntax：正例解析成功；负例必须解析失败。
- Query evaluation：结果集按变量绑定同构；`ORDER BY` 只有期望结果用 `rs:index` 编码时才比较顺序；`REDUCED` 按 `mf:LaxCardinality` 放宽。
- CONSTRUCT/DESCRIBE：结果图同构。
- ASK：布尔值相等。
- Update evaluation：执行前/后的默认图与命名图集合分别同构。
- CSV：按 CSV 的有损语义比较（字符串/`xsd:string`/`xsd:decimal` 的合法表示都可接受）。
- Federation：`SERVICE` 使用 manifest 里的 `qt:serviceData`；测试 harness 要起 mock endpoint，不能访问真实网络。

### 3.4 SPARQL 1.1 Protocol / Graph Store Protocol

**为什么必须自建**：Oxigraph 的 `oxigraph-testsuite` 没有接 protocol/GSP manifest；rudof 也没有 W3C protocol runner。官方测试用 `ht:Connection`、`ht:Request`、`ht:Response` 描述 HTTP 序列，必须有一个真 HTTP server + 状态重置。

**运行设计**

```text
for each mf:ProtocolTest / mf:GraphStoreProtocolTest:
  reset store to empty
  for each ht:Request in order:
    replace ht:connectionAuthority with test host
    replace leading /sparql/ with configured endpoint prefix
    substitute mf:expectedLocation variables from previous Location header
    send request (method, headers, body)
    assert status class (2xx/3xx/4xx)
    if expectedFormat=boolean: parse boolean, compare mf:expectedBoolean
    if expectedFormat=tabular: parse SPARQL JSON/XML/CSV/TSV, compare bindings
    if RDF: parse response, compare graph isomorphism
```

**通过标准**

- 34 条 Protocol 全过；13 条 GSP 全过；服务描述 3 条可作为加分项。
- 请求顺序是测试语义的一部分，不能并行或重排。
- GSP 的 `POST` 创建图要跟踪 `Location`；`DELETE` 允许 202，后续请求可假设删除已生效。
- 所有测试用临时端口和临时存储，跑完即销毁；不能污染开发数据。

### 3.5 SHACL 1.0

**获取**

```bash
git clone --depth 1 -b gh-pages https://github.com/w3c/data-shapes.git vendor/data-shapes
git -C vendor/data-shapes checkout 752769264137d192b3d603075162819466c46690
```

**运行（rudof 参考）**

```bash
# rudof 把 data-shapes 作为 submodule
git -C vendor/rudof submodule update --init --recursive
cargo test -p shacl
```

rudof 的 `shacl/tests/` 把 Core 的 7 个目录和 SPARQL 的 4 个目录逐个文件接线；Core 测试没有 `#[ignore]`，SPARQL 的 18 条全部带 `#[ignore]`（pre-binding 14 + component 4）。`shacl12/` 还接了 SHACL 1.2 预览套件。

**通过标准**

- 官方定义两级：
  - **partial compliance**：只比较 `sh:conforms` 布尔值；
  - **full compliance**：比较完整 validation report。
- v0.1 要求 **full compliance**：报告图与期望图同构；比较前要：
  - 去掉 `sh:details` 嵌套结果；
  - 把 `sh:ValidationReport`/`sh:ValidationResult` 的 URI 改成 bnode；
  - 克隆 `sh:resultPath` 的 bnode 结构，避免共享；
  - 只保留期望图使用的谓词（`rdf:type`、`sh:result`、`sh:conforms`、`sh:focusNode`、`sh:resultPath`、`sh:resultSeverity`、`sh:sourceConstraint`、`sh:sourceConstraintComponent`、`sh:sourceShape`、`sh:value`）；
  - `sh:resultMessage` 只有期望图里出现相同 message 时才保留。
- 121 条中 120 条 approved；1 条 proposed 单独报告，不计入 gate。
- 若 v0.1 声称支持 SHACL-SPARQL，18 条必须全过；否则明确写“SHACL Core only”，不能把 ignore 藏起来。

### 3.6 OWL 2 RL / QL

**获取**

```bash
curl -L https://www.w3.org/2009/11/owl-test/profile-RL.rdf -o vendor/owl2/profile-RL.rdf
curl -L https://www.w3.org/2009/11/owl-test/profile-QL.rdf -o vendor/owl2/profile-QL.rdf
curl -L https://www.w3.org/2009/11/owl-test/all.rdf        -o vendor/owl2/all.rdf
```

校验和（本次实测）：

```text
profile-RL.rdf  sha256 6415369555af022fedd0fe9a7d6b56eb274f89284e4109d7303540ec75c76988
all.rdf         sha256 5383f1ddf4cf2f03703a2f886f41d4e5bc375633a1cfa94a03254fd89330f8bb
```

**为什么必须自建**：`reasonable` 是 Rust OWL 2 RL reasoner，但没有 W3C OWL 2 测试套件 runner；它的 README 还明确写着 “TODO: implement remaining RDF/RDFS entailment semantics”。`horned-owl` 是解析器，不是 RL reasoner；`whelk-rs` 是 OWL EL；`sophia` 只有 RDFS/简单 entailment。没有现成 runner 可以直接跑 91 条 RL 测试。

**harness 设计**

```text
for each test:TestCase with test:profile RL:
  skip if status != Approved (report separately)
  parse premise ontology (RDF/XML inline or import)
  load into RL reasoner
  switch type:
    ConsistencyTest    -> assert consistent
    InconsistencyTest  -> assert inconsistent
    PositiveEntailment -> assert every conclusion triple is in closure
    NegativeEntailment -> assert conclusion is NOT in closure
    ProfileIdentification -> assert profile checker accepts
  record test:semantics; RL gate only uses RDF-BASED
```

要点：

- OWL 2 RL 是 **RDF-based semantics**；`test:semantics` 里同时出现 DIRECT 和 RDF-BASED 的测试，只按 RDF-BASED 跑，DIRECT-only 的测试交给 DL reasoner，不混入 RL 通过率。
- Premise/conclusion 可能是内联 RDF/XML 字符串，也可能是 `test:imports` 指向的外部 ontology；harness 要先做 import closure。
- 结论比对不是字符串比对，而是“结论三元组是否都在物化闭包里”；bnode 结论要做同构/存在变量匹配。
- 不一致测试必须 100% 通过，否则会给出错误结论；positive entailment 可以允许少量已知缺陷，但不能有 false entailment。
- QL 需要 query rewriting，不是物化；如果 #9 不做 QL，v0.1 只保留 65 条作为 nightly 预览。

**可用推理测试集**

- **LUBM**：14 条 SPARQL 查询、按大学数量扩展、固定 seed/index 可复现；适合 RL 物化 + 查询吞吐。官方页：<http://swat.cse.lehigh.edu/projects/lubm/>。
- **UOBM**：LUBM 的 OWL DL/RL 扩展，适合更大 TBox 和规则组合；官方页：<http://www.cs.ox.ac.uk/isg/tools/UOBMGenerator/>。
- **ORE 竞赛数据集**：DL reasoner 的鲁棒性/性能语料，不是 RL 一致性套件；适合作为“不会崩”的回归集，不适合作为 v0.1 gate。
- **`adrianomelo/reasoner-test-suite`**（MIT）：ontology 语料 + 统计信息，用于 reasoner 性能/鲁棒性，不是官方 conformance。

### 3.7 PROV-CONSTRAINTS

**获取**

```bash
git clone --depth 1 https://github.com/w3c/prov.git vendor/prov
git -C vendor/prov checkout aa82bd71b6bb1f7b735bf3f7f5b948fae87764f0
```

**运行**

```text
for each line in testcases/rdf-tests.txt (238 files):
  parse Turtle as PROV-O
  run normalization + 56 constraints
  expected = filename contains "-PASS-" ? valid : invalid
  compare actual validity
  if invalid: map violated constraint IDs (c30 etc.)
```

**通过标准**

- 100% 的 FAIL case 必须被拒绝（不能 false accept）；PASS case 允许 ≤2% false reject 并挂 issue；总通过率 ≥98%。
- harness 输出 56 条 constraint 的覆盖计数；未覆盖的 constraint 逐条解释，不能静默漏掉。
- 若 v0.1 只导出 PROV-O 而不做运行时校验，可把本 gate 降为“导出可被参考 validator 接受”，但不能声称“PROV-CONSTRAINTS compliant”。

**可用验证工具**

- `jamescheney/prov-constraints`：Prolog 参考 checker（`checker.pl` / `constraints.pl`），适合做独立 oracle；仓库无 LICENSE 文件，不能直接 vendoring。
- `amateescu/prov`：PHP，MIT，支持 PROV-CONSTRAINTS 校验，可作为第二 oracle。
- `pgroth/prov-constraints-validator-spin`：Python + SPINRDF，老旧，仅参考。
- Python `prov`（MIT）：强在解析/序列化 PROV-N/JSON/XML/RDF，**不提供完整的 PROV-CONSTRAINTS 校验**；不要把它当 validator。
- Rust：没有成熟 validator；heirloom 需要自己实现 normalization + constraints，或把 Python/PHP validator 作为离线 audit 工具，核心路径用 Rust 重写。

### 3.8 SKOS

**获取**

```bash
git clone --depth 1 https://github.com/skohub-io/skohub-shapes.git vendor/skohub-shapes
```

**运行**

```text
for each curated SKOS integrity condition (S9-S14, S27, S36-S37, S46):
  positive fixture -> 0 violations
  negative fixture -> ≥1 violation, mapped to condition ID
```

**通过标准**

- 10 条核心完整性条件全部有正/负 fixture；negative 必须被检出，positive 不能误报。
- 若 v0.1 支持 SKOS-XL，再加 S48/S58；否则明确写“SKOS core only, SKOS-XL out of scope”。
- 不要把“通过 skohub shapes”写成“W3C SKOS 认证”；W3C 没有这个认证。

---

## 4. Rust runner 现状：哪些能直接用，哪些要自建

| Runner / 库 | 覆盖 | 成熟度/限制 | heirloom 用法 |
|---|---|---|---|
| **Oxigraph `oxigraph-testsuite`** | RDF 1.1 NT/NQ/Turtle/TriG/RDF-XML；JSON-LD toRdf；SPARQL 1.0/1.1 query/update/federation/results；RDFC-1.0；N3 | 覆盖最广；但 `publish = false`，不是可 `cargo add` 的库；测试从 `w3c.github.io` 远程拉 manifest | Vendor/改造为自己的 `conformance` crate；保留其 evaluator/manifest 代码，替换 URL 解析为本地 fixture |
| **rudof `shacl` + `shacl/tests`** | SHACL 1.0 Core（103）与 SHACL-SPARQL（18，当前 ignore）；SHACL 1.2 预览 | Core 全接线；SPARQL 18 条 ignore；不是独立发布 harness | 若 heirloom 用 rudof `shacl`，直接复用/适配；否则用 `shacl` crate + 自建 manifest runner |
| **`json-ld` + `json-ld-testing`** | JSON-LD 1.1 expand/compact/flatten/toRdf | 官方套件 submodule；derive 宏从 manifest 生成测试；不覆盖 fromRdf/HTML/remote-doc | 若 v0.1 需要完整 JSON-LD，直接用；否则只跑 toRdf，避免“完整 processor”声明 |
| **`reasonable`** | OWL 2 RL 前向链 reasoner | 引擎可用；**没有 W3C OWL 2 runner**；README 说 RDF/RDFS entailment 仍有 TODO；性能对比是 Brick 模型，非标准套件 | 作为 RL 引擎候选；自建 harness 跑 91 条；先做 rule coverage 差距分析 |
| **`sophia`** | RDF 1.2 API、Turtle/JSON-LD/XML parser、SPARQL 1.2 partial、RDFS/Simple entailment | 没有 W3C manifest runner；测试是自带 sample | 不作为 conformance runner；可作为 parser/对照实现 |
| **无现成 runner** | SPARQL Protocol/GSP、OWL 2 RL/QL、PROV-CONSTRAINTS、SKOS integrity | — | 自建 harness（见 §3.4、§3.6、§3.7、§3.8） |

结论：

- **能复用**：RDF 1.1、RDFC、JSON-LD（部分）、SPARQL Query/Update、SHACL Core。
- **必须自建**：SPARQL Protocol/GSP、OWL 2 RL/QL、PROV-CONSTRAINTS、SKOS。
- **不能直接依赖**：`oxigraph-testsuite` 未发布；`rudof` 测试 harness 未独立发布；`reasonable` 没有官方套件接线。所有复用都要 vendor + license notice + 固定 commit。

---

## 5. 官方 W3C 测试 vs 社区补充

### 5.1 官方

- **RDF 1.1 / Turtle / N-Triples / N-Quads / TriG / RDF/XML / RDF Semantics / RDFC**：W3C RDF WG 产物；`w3c/rdf-tests` 现由 RDF Tests CG 与 RDF & SPARQL WG 共同维护。许可证是 W3C Test Suite License + W3C 3-Clause BSD 双许可（`LICENSE.md` 明说）。manifest 里同时有 `rdft:Approved`、`rdft:Proposed`、`rdft:NotClassified`；gate 按 manifest entries 统计，但报告要单列 approval 状态。
- **SPARQL 1.1 Query/Update/Protocol/GSP/Entailment/Results**：W3C SPARQL WG 产物，同上双许可；`sparql/README.md` 说明测试由 RDF & SPARQL WG 维护、测试本体在 `rdf-tests`。
- **JSON-LD 1.1**：W3C JSON-LD WG 产物，测试在 `w3c/json-ld-api/tests`，同上双许可。
- **SHACL 1.0**：W3C RDF Data Shapes WG 产物，`w3c/data-shapes` gh-pages；W3C Software and Document License。
- **OWL 2**：W3C OWL WG 产物；官方仓库入口已跳到归档 `https://www.w3.org/2009/11/owl-test/`；归档本身没有独立 LICENSE 文件，法务上按 W3C Document License/Test Suite License 处理，正式 vendoring 前要确认。
- **PROV-CONSTRAINTS**：W3C Provenance WG 产物；`w3c/prov` 的 `testcases/` 是官方测试，但仓库没有 LICENSE 文件；同样需要法务确认。
- **SKOS**：W3C Recommendation，但 **没有官方测试套件**，规范明确不定义正式 conformance。

### 5.2 官方但仍是草案（不进 v0.1 gate）

- RDF 1.2（2026-11-13 Candidate Recommendation Draft）、SPARQL 1.2（2026-11-12 Working Draft）、SHACL 1.2（2026-11-10 Working Draft）。
- 这些套件适合 nightly 预览和未来迁移，不应写进 v0.1 的“兼容”声明。

### 5.3 社区补充

| 补充 | 用途 | 许可证 | 注意 |
|---|---|---|---|
| `skohub-io/skohub-shapes` | SKOS 结构约束的 SHACL 形状 | Apache-2.0 | 不是 W3C 官方测试；只能作为起点/交叉验证 |
| `rudof-project/rudof-shacl-benchmarks` | SHACL 验证性能对比（ERA/ICDD/LUBM） | MIT | 性能基准，不是 conformance |
| `weso/shacl-validation-benchmarks` | SHACL 引擎对比 | 未标注 | 数据/形状可参考，不能直接再分发 |
| `adrianomelo/reasoner-test-suite` | OWL reasoner 本体语料/统计 | MIT | 鲁棒性/性能，不是官方测试 |
| ORE 竞赛 | OWL DL reasoner 性能 | 各数据集单独条款 | 只作参考 |
| `semantica-agi/semantica` benchmarks | 对照/竞品方法 | MIT | 见 §6；不可作为验收基线 |

---

## 6. Semantica 基准审计

### 6.1 官方主张

Semantica README 的 Performance 表（`README.md:1178-1187`，commit `3ebdb95`）写着：

| Operation | Before | After | Improvement |
|---|---|---|---|
| Node search (118k nodes) | 24 ms | 0.004 ms | 6,000× faster |
| Embedding cache hit | cold load | revision-based cache | 10× throughput |
| Semantic deduplication | baseline | optimized candidate gen | 6.98× faster |
| Candidate generation | baseline | blocking strategy | 63.6% faster |

脚注明确说：118,000-node production graph，AMD EPYC / 64 GB RAM；dedup/candidate-generation 是 **historical measurements recorded in CHANGELOG rather than an automated `tests/` assertion**；结果随硬件、拓扑、backend 变化；让你跑 `pytest tests/vector_store/test_performance_benchmarks.py -s`。

CHANGELOG 的 PR #481（`CHANGELOG.md:1763`）写的是 “warm-query time 24 ms → 0.004 ms on 118 k-node graph”，实现是 inverted index + LRU + `bisect.insort`。Explorer 文档另给 BFS path <50 ms、simple SPARQL SELECT <20 ms（`docs/reference/explorer.md:377-382`）。

### 6.2 仓库里实际有什么

1. **`semantica/benchmarks/`（memory QA harness）**
   - README 明确说 “phase 1 … not a leaderboard: no dataset ships with it beyond a tiny hand-written smoke set, and no result table is published yet.”（`semantica/benchmarks/README.md:7-9`）
   - 数据集：`sample`（6 题，CC0）、HotPotQA（CC BY-SA 4.0）、MuSiQue（CC BY 4.0）、LoCoMo（CC BY-NC 4.0）。
   - 指标：token F1 + normalized exact match；系统：lexical、semantica、mem0、graphiti、cognee。
   - 它测的是 **memory QA**，不是图搜索/SPARQL/推理。

2. **`tests/vector_store/test_performance_benchmarks.py`**
   - 阈值：single decision 100 ms、batch 10 ms/decision、search 50 ms、precedent 100 ms、context retrieval 150 ms、memory 1 KB/decision（约 `:40-52`）。
   - 数据：100 条决策、500 个文档、in-memory vector store；**没有 118k 节点图、没有 0.004 ms 断言**。

3. **CHANGELOG 的 “137+ benchmarks”**
   - `CHANGELOG.md:2034` 说 PR #289 加了 137+ benchmarks、Z-score、GitHub Actions、`python benchmarks/benchmark_runner.py`。
   - 但当前仓库 **没有 `benchmarks/` 目录**，也没有 `benchmark_runner.py`；CHANGELOG 自己还记录过 `benchmark.yml` 引用不存在的 `benchmarks/requirements.txt` 并失败。
   - 因此这条主张不能作为可复现证据。

### 6.3 结论

- `118k / 24 ms → 0.004 ms / 6000×` 是 **v0.5.0 的历史营销数字**，缺数据集、查询集、硬件脚本、重复次数、冷/热缓存区分；不能作为 heirloom 的性能基线，也不能写进 heirloom 的承诺。
- `0.004 ms` 更像 **已缓存的精确索引命中**，不能外推到图遍历、SPARQL、SHACL、OWL RL 或决策写入。
- 与 Semantica 对比时，必须 **同数据集、同操作、同硬件、同重复次数**，并分开报告“缓存命中/精确查找”和“通用查询/遍历/推理”。

### 6.4 建议的 Semantica 对比方法（供 #17）

1. **QA 对照**：用 `semantica.benchmarks` 的 HotPotQA 200 题 / MuSiQue 200 题 / LoCoMo 10 段，固定 reader、固定检索深度；报告 token F1、EM、p50/p95 延迟、峰值 RSS。heirloom 提供同协议的 `MemorySystem` adapter。
2. **图操作对照**：自建 118k 节点 / ~500k 边幂律图，固定 10k 查询；分别测 exact lookup、1-hop/2-hop、BFS、decision write、causal trace、SPARQL；不要拿 Semantica 的 cached 0.004 ms 对 heirloom 的通用 SPARQL。
3. **端到端对照**：同一份合同/临床/金融 fixture，固定抽取结果（不用 LLM），测 import → graph → validate → query → export；比较总时长与正确性，不比较模型质量。
4. **记录**：Semantica commit `3ebdb95`、Python 3.11、backend、是否 warm cache；heirloom commit、Rust release、Oxigraph 版本。

---

## 7. 映射到 v0.1 验收门槛（建议）

> 这些是 **建议门槛**，需要 #17 正式拍板。原则：correctness gate 不允许“silent skip”；性能 gate 允许先测 baseline 再校准，但不能悄悄降低。

### 7.1 正确性 gate

| ID | Gate | 套件/规模 | 通过阈值 | 阻断 v0.1？ |
|---|---|---|---:|---|
| C1 | RDF 1.1 语法/求值 | Turtle 313 + NT 70 + NQ 87 + TriG 357 + RDF/XML 166 = 993 | 100% entries；0 skip | 是 |
| C2 | RDF 1.1 Semantics | 62 | 若做 RDFS/OWL entailment：≥95%；否则 N/A | 条件 |
| C3 | RDFC 1.0 | 86 | 100% | 是（确定性导出） |
| C4 | JSON-LD toRdf | 467 | ≥95%；19 条 optional/edge 逐条列明，release 不允许隐藏 | 是（若导出 JSON-LD） |
| C5 | JSON-LD expand/compact/flatten | 690 | 若声明完整 processor：≥98%；否则 toRdf only | 条件 |
| C6 | SPARQL Query syntax | 106（65 pos + 41 neg） | 100% | 是 |
| C7 | SPARQL Query eval（核心） | 261（247 + 7 service + 7 results） | ≥98%；0 未解释失败 | 是 |
| C8 | SPARQL Federation | 10（7 eval + 3 syntax） | ≥95% | 条件（若支持 SERVICE） |
| C9 | SPARQL Update syntax/eval | 63 syntax（42 pos update + 13 neg update + 8 neg syntax）+ 94 eval | syntax 100%；eval ≥95% | 是 |
| C10 | SPARQL Results（JSON/CSV/TSV） | 11（4 CSV + 7 eval）+ 4 JSON | ≥98% | 是 |
| C11 | SPARQL Protocol | 34 | 100% | 条件（若暴露 SPARQL endpoint） |
| C12 | Graph Store Protocol | 13 | 100% | 条件（若暴露 GSP） |
| C13 | SHACL Core | 103 | 100% full report compliance | 是（若声明 SHACL） |
| C14 | SHACL-SPARQL | 18 | 100%，或明确“Core only”并记 issue | 条件 |
| C15 | OWL 2 RL | 70 approved | consistency/inconsistency/negative 100%；positive entailment ≥95% | 条件（若 #9 做 RL） |
| C16 | OWL 2 QL | 45 approved | 同上；QL query rewriting 另测 | 条件（v0.1 不建议） |
| C17 | PROV-CONSTRAINTS | 238 RDF | ≥98%；FAIL 100% 拒绝 | 条件（若运行时校验） |
| C18 | SKOS integrity | 10 核心条件 | 100% 正/负 fixture | 条件（若声明 SKOS 校验） |
| C19 | SPARQL Entailment | 70 | 若做 entailment：≥95%；否则 N/A | 条件 |

### 7.2 确定性与审计 gate

| ID | Gate | 方法 | 阈值 |
|---|---|---|---:|
| D1 | 输出确定性 | 同一输入跑 10 次，RDFC-1.0 canonical N-Quads SHA-256 | 10/10 相同 |
| D2 | 决策链完整 | 每个 decision 至少有 agent/activity/entity/policy version/evidence | 100% demo decisions |
| D3 | 崩溃恢复 | 在 load/update/export 三个点 kill -9，重启后校验 | 3/3 无丢失、可重跑、幂等 |
| D4 | 套件可复现 | `suites.lock.toml` + SHA-256 + 本地 fixture；断网运行 | 0 网络请求、0 未锁定版本 |

### 7.3 性能 gate（建议）

参考机 **R1**：8 vCPU（AMD EPYC 7B13 或同级）、32 GB RAM、NVMe、Linux x86_64、release、单线程（除非注明）。**R2**：Apple M2 Pro 12 核 / 32 GB / macOS，仅开发 smoke。

| ID | 工作负载 | 数据集 | 目标（R1） |
|---|---|---|---:|
| P1 | 解析吞吐 | BSBM 1k（27,886 triples / 6.5 MB） | N-Triples ≥60 MB/s；Turtle ≥30 MB/s；JSON-LD ≥15 MB/s；RDF/XML ≥20 MB/s |
| P2 | 加载 | 1M triples | in-memory ≤30 s；on-disk ≤60 s；bulk ≥50k triples/s |
| P3 | SPARQL Query | BSBM 1k explore mix | ≥500 qps；p95 ≤20 ms；warm |
| P4 | SPARQL Query 扩展 | BSBM 10k / 100k | p95 ≤100 ms / ≤250 ms |
| P5 | SPARQL Update | BSBM 1k exploreAndUpdate | ≥100 ops/s；p95 ≤50 ms |
| P6 | SHACL | 100k focus nodes × 10 shapes | ≤10 s；1M triples × 20 shapes ≤60 s |
| P7 | OWL 2 RL | LUBM(1)（约 10^5 triples） | closure ≤15 s；1M inferred triples ≤60 s；增量 1k ≤1 s |
| P8 | PROV | 100k events DAG | validate ≤10 s；20-hop trace p95 ≤100 ms |
| P9 | Snapshot/Export | 1M triples | snapshot ≤5 s；restore ≤10 s；Turtle ≤10 s；JSON-LD ≤20 s；RDFC ≤30 s |
| P10 | 内存 | 1M / 10M triples | ≤2 GB / ≤12 GB RSS |
| P11 | 并发 | 1M triples，16 readers + 1 writer | ≥5k qps；p99 ≤50 ms；writer stall ≤100 ms |
| P12 | 端到端 demo | D1 1k cases + D2 50 contracts + D3 100 patients | ≤10 min（不含 LLM） |

相对基线（同一台 R1）：

- RDF/SPARQL parse/load/query：不超过 Oxigraph 0.4.x 的 **1.2×**。
- SHACL：不超过 rudof `shacl` 的 **2×**。
- OWL RL：不超过 `reasonable` 的 **2×**。
- PROV：不超过参考 checker 的 **2×**。
- 回归：同一 workload 比上一 release 慢 **>10%** 阻断，5–10% 警告。

这些数字是 **初始目标**；第一次 baseline 之后可在 #17 调整，但必须在文档里记录调整理由，不能静默放宽。

---

## 8. 基准 harness 设计

### 8.1 目录布局

建议放在主仓库（具体路径由 #16 决定）：

```text
conformance/
  suites.lock.toml          # 每个 suite 的 URL/commit/sha256/license/runner/gate
  src/
    lib.rs
    manifest.rs             # mf:, qt:, ut:, sht:, ht: 解析
    report.rs               # JSON/JUnit/Markdown
    runners/
      rdf.rs
      jsonld.rs
      sparql.rs
      shacl.rs
      owl.rs
      prov.rs
      skos.rs
      protocol.rs
  fixtures/                 # 本地 vendored suite（可选；也可运行时 fetch 到 cache）
  tests/smoke.rs

bench/
  datasets.toml             # URL/sha256/size/license/triples
  workloads/
    bsbm/
    watdiv/
    lubm/
    prov/
    demo/
  src/main.rs
  reports/
```

### 8.2 `suites.lock.toml`

```toml
version = 1

[[suite]]
id = "rdf11-turtle"
official = true
source = "https://github.com/w3c/rdf-tests"
commit = "7b5afb91857a9c282c03d2e45d3f76485797c901"
path = "rdf/rdf11/rdf-turtle/manifest.ttl"
sha256 = "<manifest sha256>"
license = "W3C-Test-Suite-License OR W3C-3-Clause-BSD"
runner = "oxigraph-testsuite"
gate = "C1"
```

规则：

- 不允许 `main` / `latest` / 浮动 URL。
- 每个 suite 记录 manifest 的 SHA-256；fetch 后校验，否则失败。
- 没有 LICENSE 的 suite（OWL/PROV）在 lock 文件里写 `license = "UNVERIFIED"`，CI 允许跑但 release 前必须法务确认；不能把 UNVERIFIED 的 fixture 打进发行包。

### 8.3 报告格式

每次运行输出 JSON + JUnit XML + Markdown：

```json
{
  "schema_version": 1,
  "suite": {
    "id": "rdf11-turtle",
    "commit": "7b5afb9...",
    "license": "W3C-Test-Suite-License OR W3C-3-Clause-BSD"
  },
  "environment": {
    "git_commit": "heirloom...",
    "rustc": "1.xx",
    "cpu": "AMD EPYC 7B13",
    "cores": 8,
    "ram_gb": 32,
    "os": "Linux 6.x"
  },
  "counts": {"total": 313, "passed": 313, "failed": 0, "skipped": 0},
  "duration_ms": 1234,
  "failures": [
    {"id": "...#IRI_with_all_punctuation", "kind": "result_mismatch", "detail": "..."}
  ]
}
```

约定：

- `skipped` 必须为 0 才能 release；开发期 skip 要带 issue + 到期日。
- 失败必须给出 test ID、期望/实际摘要、复现命令。
- 每次 CI 保存 artifact；nightly 保留 30 天。
- 报告用 `result_hash` 记录 canonical 输出，便于 D1 比较。

### 8.4 数据集清单

| ID | 数据集 | 规模 | 许可证 | 获取 | 用途 |
|---|---|---|---:|---|---|
| DS1 | BSBM v3.1 1k | 27,886 triples / 6.5 MB | Apache-2.0 | [Zenodo 12663333](https://zenodo.org/records/12663333) `dataset-1000.nt.bz2` | CI parse/load/query |
| DS2 | BSBM 10k | 62.5 MB compressed | Apache-2.0 | 同上 `dataset-10000.nt.bz2` | nightly |
| DS3 | BSBM 100k | 645 MB compressed | Apache-2.0 | 同上 `dataset-100000.nt.bz2` | release |
| DS4 | WatDiv v0.6 scale 1 | 105,257 triples | 未标注（合成数据） | <https://dsg.uwaterloo.ca/watdiv/> | 查询多样性 |
| DS5 | LUBM(1) | 约 10^5 triples | 未标注（合成数据） | <http://swat.cse.lehigh.edu/projects/lubm/> | OWL RL/查询 |
| DS6 | UOBM | 可配置 | 未标注 | <http://www.cs.ox.ac.uk/isg/tools/UOBMGenerator/> | RL 压力 |
| DS7 | PROV 约 294 cases | 238 RDF 文件 | W3C 托管（待确认） | `w3c/prov` | PROV 校验 |
| DS8 | 合成 118k 节点图 | 118k nodes / ~500k edges | CC0（自建） | `bench/generators/powerlaw.py --seed 20261115` | 图操作/对照 |
| DS9 | 合成决策链 | 1,000 cases / ~100k triples | CC0（自建） | `bench/generators/decisions.py` | 端到端 |

BSBM 的 query mix 用 `explore-1000.csv.bz2`（13,750 条查询）和 `businessIntelligence-1000.csv.bz2`；update 用 `exploreAndUpdate-1000.csv.bz2`。这些文件都在同一个 Zenodo record，许可证 Apache-2.0。

### 8.5 操作矩阵

| 操作 | 输入 | 输出指标 | 备注 |
|---|---|---|---|
| `parse` | NT/Turtle/JSON-LD/RDF-XML | MB/s、triples/s、峰值 RSS | 不建 store；独立进程 |
| `load` | 1M triples | 秒、triples/s、RSS | in-memory / on-disk / bulk 三种 |
| `query` | BSBM/WatDiv/LUBM | qps、p50/p95/p99、结果 hash | warm/cold 分开 |
| `update` | BSBM exploreAndUpdate | ops/s、p95、最终状态 hash | 每次重置 store |
| `reason` | LUBM/UOBM | closure 秒、inferred triples、增量延迟 | OWL RL |
| `validate` | W3C SHACL + 生成 shapes | focus nodes/s、p95、report hash | Core / SPARQL 分开 |
| `provenance` | PROV suite + 合成 DAG | validate 秒、trace p95 | 56 constraints 覆盖 |
| `trace` | 决策链 | 20-hop p95、路径正确性 | 因果/时序 |
| `snapshot` | 1M triples | create/restore 秒、hash | 崩溃恢复配合 |
| `export` | 1M triples | Turtle/NT/JSON-LD 秒、RDFC 秒 | 结果可解析 |
| `e2e` | D1–D3 | 总秒、正确性、审计完整 | 不含 LLM |
| `concurrency` | 1M triples | qps、p99、writer stall | 16 readers + 1 writer |
| `recovery` | 1M triples | kill -9 后恢复时间/丢失 | 3 个断点 |

### 8.6 重复次数与统计

- **Micro**（parse/query/validate/trace）：5 次 warmup + 30 次测量；每个 workload 至少 3 个独立进程重复。
- **Macro**（load/reason/snapshot/e2e）：1 次 warmup + 5 次测量；每次 fresh process + 空 store。
- **Cold**：只在专用 runner 上用 `drop_caches` 跑 3 次；否则只声明 “cold process, warm FS cache”。
- **Concurrency**：10 s warmup + 60 s 测量；报告 qps、p50/p95/p99、错误率。
- **Determinism**：10 次完整运行；比较 canonical hash；任何一次不同即失败。
- **统计**：报告 median、p95、p99、bootstrap 95% CI；不剔除 outlier；同时给出样本数。
- **回归判定**：median 慢 >10% 阻断；5–10% 警告；p99 单独观察，避免尾延迟被中位数掩盖。

### 8.7 参考机与隔离

- 性能 gate 只在 **R1 专用机**上跑；GitHub-hosted runner 只跑 correctness。
- 关闭 turbo/节能，`cpupower frequency-set -g performance`；用 `taskset` 固定核；关闭其他容器。
- 记录 CPU model、microcode、RAM、NVMe、OS、rustc、feature flags、allocator。
- 报告 `git_commit`、`oxigraph`/`rudof`/`reasonable` 版本；不同版本不比较。
- 基准进程和被测进程分开；避免 benchmark 自己污染 cache。

### 8.8 CI

```yaml
# .github/workflows/conformance.yml（示意）
on: [pull_request]
jobs:
  conformance:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - run: cargo xtask conformance fetch --locked
      - run: cargo xtask conformance run --all --profile ci
      - uses: actions/upload-artifact@v4
        with:
          name: conformance-report
          path: target/conformance/
```

```yaml
# .github/workflows/bench.yml（示意，nightly + 手动）
on:
  schedule: [{cron: "17 3 * * *"}]
  workflow_dispatch:
jobs:
  bench:
    runs-on: [self-hosted, linux, perf-r1]
    steps:
      - uses: actions/checkout@v4
      - run: cargo xtask bench --suite bsbm-1k --runs 30 --json target/bench/bsbm-1k.json
      - run: cargo xtask bench compare --baseline main --threshold 0.10
```

### 8.9 报告样例

| workload | p50 | p95 | p99 | throughput | RSS | hash |
|---|---:|---:|---:|---:|---:|---|
| BSBM 1k explore | 1.8 ms | 3.2 ms | 6.1 ms | 620 qps | 410 MB | `sha256:...` |
| SHACL 100k×10 | 4.1 s | 4.8 s | 5.2 s | 24k nodes/s | 1.1 GB | `sha256:...` |
| OWL RL LUBM(1) | 8.4 s | 9.1 s | 9.5 s | 12k triples/s | 2.3 GB | `sha256:...` |

---

## 9. 可复现端到端演示数据集

目标：每个 demo 都能在 **断网、固定 seed、固定 fixture** 下跑出可比较的图、查询结果和审计报告；LLM 只在 `--live` 模式启用，不作为 gate。

### D1 受监管决策链（合成，CC0）

- **场景**：信贷/福利资格审批。申请人提交材料 → 抽取事实 → 规则评估 → 人工复核 → 决策 → 申诉/更正。
- **规模**：1,000 cases；约 30–40 triples/case；总计约 100k triples（含 PROV）。
- **生成器**：`bench/generators/decisions.py --seed 20261115 --cases 1000`；固定申请人/金额/政策版本/时间线。
- **模型**：Applicant、Application、Document、ExtractedFact、PolicyVersion、Decision、Reviewer、Appeal；PROV Activity/Agent/Entity。
- **SHACL**：decision 必须有 policy version、rationale、evidence、reviewer（adverse action）；事实必须来自 document；时间窗合法。
- **查询**：为什么拒绝 X？使用了哪版政策？哪些事实在 T1 与 T2 之间变化？谁批准了例外？回滚后审计链是否完整？
- **预期产物**：`expected/graph.nq.sha256`、`expected/answers/*.json`、`expected/prov/*.ttl`、`expected/audit.json`。
- **验收**：D1 + DS8 + DS9；10/10 hash 一致；100% decision 有完整 PROV；3 个时间旅行场景正确。

### D2 合同义务图（CUAD + ContractNLI，CC BY 4.0）

- **数据**：CUAD 50 份合同 + ContractNLI 20 份；只放下载脚本，不在仓库再分发原始文本（避免许可证/隐私问题）。
- **抽取 fixture**：固定 JSON（party、date、term、renewal、termination、liability、governing law）。
- **图**：Contract、Party、Clause、Obligation、Date、Jurisdiction；PROV 记录来源 clause/char offset。
- **SHACL**：合同必须有 governing law、effective date、renewal 条款；义务必须有 responsible party 和 trigger。
- **查询**：哪些合同自动续约？哪些义务在 90 天内到期？哪一条款支持“termination for convenience”？
- **许可证**：CUAD/ContractNLI 都是 CC BY 4.0（CUAD 以数据集页面为准）；demo 输出必须带 attribution，不能去掉版权声明。

### D3 临床队列（Synthea，Apache-2.0）

- **数据**：Synthea 100 个合成患者；CSV/FHIR R4；不含真实 PHI。
- **图**：Patient、Encounter、Condition、Observation、Medication、Provider；用最小 FHIR RDF 映射（不追求完整 FHIR ontology）。
- **SHACL**：Observation 必须有 code/value/unit/date；Medication 必须有 start/end；Patient 必须有 birthDate。
- **查询**：某病种 30 天再入院？某药物与肾功能阈值？时间窗内的 observation 趋势？
- **注意**：MIMIC-IV **不进入 CI/仓库**；它需要 PhysioNet credentialed access 和 DUA，许可证是 PhysioNet Credentialed Health Data License 1.5.0，不能再分发。可在本地用 `--mimic` 做可选验证。

### D4 财务重述/审计（SEC XBRL，公共领域）

- **数据**：SEC Financial Statement Data Sets 的 `sub.txt`/`num.txt`/`pre.txt`/`tag.txt`，选 10 家公司、20 份 10-K/10-Q；需要 User-Agent 和 SEC Fair Access 限速。
- **图**：Filing、Company、Fact、Concept、Period、Unit、FilingAgent；PROV 记录 filing accession、提取时间、restatement 关系。
- **SHACL**：每个 Fact 必须有 concept/period/unit/source；restatement 必须指向原 fact；金额单位必须一致。
- **查询**：某公司 2024 revenue 与 2023 重述差异？哪些 fact 来自同一 filing？重述链的根因？
- **许可证**：美国联邦政府作品通常视为公共领域，但访问受 SEC 条款约束；只放下载脚本和派生图，不批量再分发原始文件。

### D5 知识图谱构建（可选，Wikipedia/Wikidata）

- **数据**：1,000 篇 Wikipedia 文章（CC BY-SA 4.0）+ 对应 Wikidata 实体（CC0）；固定 dump 日期。
- **图**：Article、Entity、Mention、Relation、Topic；抽取 fixture（不跑 LLM）或 `--live` LLM。
- **用途**：验证“文档 → 实体 → 关系 → 图 → 查询”的端到端；不作为 conformance gate。
- **注意**：CC BY-SA 的 share-alike 会影响派生文本分发；图数据尽量用 Wikidata CC0 部分。

---

## 10. 实施计划（建议）

单人 + agent 并行，约 **4–6 周**；可与 #17 的验收设计并行。

| 阶段 | 工作 | 产出 | 退出标准 |
|---|---|---|---|
| M0（2 天） | 建 `conformance/` 骨架、`suites.lock.toml`、license 清单 | lock + fetch 脚本 | 断网可跑；所有 suite 有 commit/sha256 |
| M1（1 周） | 接 Oxigraph RDF/JSON-LD/SPARQL runner；本地 fixture 映射 | C1/C3/C4/C6/C7/C9/C10 报告 | 报告 JSON/JUnit 可读；0 silent skip |
| M2（1 周） | SHACL runner（rudof 适配）+ Protocol/GSP HTTP harness | C11–C14 报告 | SHACL Core 100%；protocol 34/34 |
| M3（2 周） | OWL RL harness（reasonable 或自研）+ PROV validator + SKOS fixtures | C15/C17/C18 报告 | RL 70 approved 达标；PROV 238 达标；SKOS 10 条 |
| M4（2 周） | benchmark harness + BSBM/WatDiv/LUBM/合成数据 + R1 baseline | bench 报告 + 基线 | P1–P10 有数；回归阈值生效 |
| M5（1 周） | D1–D4 demo + CI + 文档 | demo 脚本/expected hash | 断网 demo 通过；nightly 绿 |

优先级：

1. **P0**：C1/C3/C4/C6/C7/C9/C10/C13 + D1/D4 + P1/P3/P10。
2. **P1**：C11/C12/C14/C15/C17 + P6/P7/P8。
3. **P2**：C18、C5、C8、C16、D3/D5。

如果时间不够，先保证 **RDF/SPARQL/SHACL Core + 确定性 + 决策链审计**；OWL RL/PROV/SKOS 可以按 #9/#8 的范围延后，但不能在没有测试的情况下声称兼容。

---

## 11. 风险与开放问题

1. **OWL 2 官方仓库已迁移/归档**：`https://www.w3.org/2009/owl-test-cases` 现在只是跳转说明，真实文件在 `https://www.w3.org/2009/11/owl-test/`；归档没有明确 LICENSE，vendoring 前要法务确认。
2. **PROV 测试仓库没有 LICENSE 文件**：`w3c/prov` 是 W3C 托管，但 `testcases/` 的再分发条款不明确；release 包不要直接带 fixture，改为下载脚本 + 校验和。
3. **Oxigraph runner 不是 hermetic**：测试从 `w3c.github.io` 拉 manifest；上游变更会让 CI 漂移。必须固定 commit + 本地镜像。
4. **`oxigraph-testsuite` 未发布**：不能 `cargo add`；vendor 后要跟踪上游，避免 fork 漂移。
5. **rudof 的 SHACL-SPARQL 18 条被 ignore**：如果 heirloom 直接复用 rudof，不能声称完整 SHACL-SPARQL；要么补实现，要么明确 Core only。
6. **`reasonable` 不是认证 RL reasoner**：README 说 RDF/RDFS entailment 仍有 TODO；先跑 91 条 RL 测试，别假设 100% 通过。
7. **OWL direct vs RDF-based semantics**：RL 只适用于 RDF-based；混跑 DIRECT 测试会得出错误结论。harness 必须按 `test:semantics` 过滤。
8. **SPARQL Protocol 依赖 HTTP 端点**：如果 v0.1 不暴露 SPARQL endpoint/GSP，C11/C12 只能标 N/A；不能声称协议兼容。
9. **JSON-LD “完整 processor” 成本高**：全量 1,279 条包含 HTML/remote-doc/fromRdf；v0.1 若只导出 toRdf，应明确范围，不要用 467 条通过率暗示全量兼容。
10. **Semantica 数字不可复现**：118k/0.004 ms 没有数据集/查询/脚本；不要写进 heirloom 承诺，也不要用它当 baseline。
11. **许可证**：CUAD/ContractNLI 是 CC BY 4.0（要 attribution）；MIMIC-IV 是 credentialed（不能再分发）；SEC 有 Fair Access；W3C OWL/PROV 的 fixture 条款待确认。
12. **硬件噪声**：GitHub-hosted runner 不适合性能 gate；必须自建/专用 runner，否则数字不可比。
13. **RDF 1.2 / SPARQL 1.2 / SHACL 1.2**：都还是草案；v0.1 不要声明，nightly 预览即可。
14. **Windows**：v0.1 不支持；RDF/XML 和路径大小写测试只在 Linux/macOS 跑。

需要 #17 拍板的问题：

- v0.1 是否暴露 SPARQL 1.1 Protocol / GSP？决定 C11/C12 是否阻断。
- #9 是否把 OWL 2 RL 纳入 v0.1？QL 是否完全不做？
- SHACL-SPARQL 是否在写入路径使用？决定 C14 是否阻断。
- PROV 校验是运行时能力还是仅导出/离线审计？决定 C17 的阈值。
- SKOS 是“导入+结构校验”还是“完整 integrity 校验”？决定 C18 的范围。
- JSON-LD 是“导出 toRdf”还是“完整 processor”？决定 C5/C4 的范围。
- 参考机 R1 的实际配置与 CI 预算。
- OWL/PROV fixture 的法务结论。

### 11.1 方法与局限

- 本文的测试数量来自 2026-11-15 的浅克隆与 manifest 解析（rdflib + 文本统计），不是完整执行结果；个别 manifest 的 typed resource 数与 entries 数不同，文中已注明。
- **没有跑完整 W3C 套件**，也**没有在 R1 上实测性能**；§7.3 的数字是建议门槛，不是已测结果。
- **没有复现 Semantica 的 118k 图**；§6 是静态审计 + 官方文本对照。
- OWL/PROV 归档的许可证没有经过法务审查；正式 vendoring 前必须确认。
- 测试套件与工具都在快速演进；实施时应以 `suites.lock.toml` 的 commit 为准，不要用本文的 URL 直接覆盖。

---

## 12. 参考来源（本次核对）

**W3C 规范/测试**

- RDF 1.1 Test Cases：<https://www.w3.org/TR/rdf11-testcases/>
- Turtle 1.1：<https://www.w3.org/TR/turtle/>
- RDF 1.1 Semantics：<https://www.w3.org/TR/rdf11-mt/>
- JSON-LD 1.1 API：<https://www.w3.org/TR/json-ld11-api/>
- JSON-LD Test Suite：<https://w3c.github.io/json-ld-api/tests/>
- SPARQL 1.1 Query：<https://www.w3.org/TR/sparql11-query/>
- SPARQL 1.1 Update：<https://www.w3.org/TR/sparql11-update/>
- SPARQL 1.1 Protocol：<https://www.w3.org/TR/sparql11-protocol/>
- SPARQL 1.1 Graph Store HTTP Protocol：<https://www.w3.org/TR/sparql11-http-rdf-update/>
- SPARQL 1.1 Entailment：<https://www.w3.org/TR/sparql11-entailment/>
- SHACL 1.0：<https://www.w3.org/TR/shacl/>
- SHACL Test Suite：<https://w3c.github.io/data-shapes/data-shapes-test-suite/>
- OWL 2 Conformance：<https://www.w3.org/TR/owl2-test/>
- OWL 2 Test Archive：<https://www.w3.org/2009/11/owl-test/>
- PROV-CONSTRAINTS：<https://www.w3.org/TR/prov-constraints/>
- PROV Test Cases：<https://github.com/w3c/prov/tree/aa82bd71b6bb1f7b735bf3f7f5b948fae87764f0/testcases>
- SKOS Reference：<https://www.w3.org/TR/skos-reference/>
- RDF Dataset Canonicalization：<https://www.w3.org/TR/rdf-canon/>

**测试仓库（固定 commit）**

- `w3c/rdf-tests` @ `7b5afb91857a9c282c03d2e45d3f76485797c901`：<https://github.com/w3c/rdf-tests>
- `w3c/data-shapes` @ `752769264137d192b3d603075162819466c46690`：<https://github.com/w3c/data-shapes>
- `w3c/json-ld-api` @ `55514738d854e56bb8bad4c53cda2f0e23be4a50`：<https://github.com/w3c/json-ld-api>
- `w3c/prov` @ `aa82bd71b6bb1f7b735bf3f7f5b948fae87764f0`：<https://github.com/w3c/prov>
- `w3c/rdf-canon` @ `5ffaaa13c0e19a2a2f9e60d0ecd9efaf3edab633`：<https://github.com/w3c/rdf-canon>

**Rust 实现**

- Oxigraph @ `78734be853a4bec714ba09bbfad790e64f3a1150`，`testsuite/`：<https://github.com/oxigraph/oxigraph/tree/78734be853a4bec714ba09bbfad790e64f3a1150/testsuite>
- rudof @ `5e361daf3f7d30b31f0d545a179a67976f95541b`，`shacl/tests/`：<https://github.com/rudof-project/rudof/tree/5e361daf3f7d30b31f0d545a179a67976f95541b/shacl/tests>
- `json-ld` @ `3f5b63ebd9ba15dba4dc9f8b6ae21d58c301cd21`，`tests/`：<https://github.com/timothee-haudebourg/json-ld/tree/3f5b63ebd9ba15dba4dc9f8b6ae21d58c301cd21/tests>
- `reasonable` @ `fecee2b6d3713f97c86aad56e099674dcacea75b`：<https://github.com/gtfierro/reasonable>
- `sophia` @ `25d724049cc227523ce88b88aca228520f585ab1`：<https://github.com/pchampin/sophia_rs>
- `skohub-shapes`：<https://github.com/skohub-io/skohub-shapes>

**基准/对照**

- BSBM v3.1 pre-generated files，DOI `10.5281/zenodo.12663333`，Apache-2.0：<https://zenodo.org/records/12663333>
- LUBM：<http://swat.cse.lehigh.edu/projects/lubm/>
- WatDiv：<https://dsg.uwaterloo.ca/watdiv/>
- UOBM：<http://www.cs.ox.ac.uk/isg/tools/UOBMGenerator/>
- LDBC SNB：<https://ldbcouncil.org/benchmarks/snb/>
- `rudof-project/rudof-shacl-benchmarks`：<https://github.com/rudof-project/rudof-shacl-benchmarks>
- Semantica @ `3ebdb95`：
  - README performance：<https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/README.md#L1178-L1187>
  - benchmark harness README：<https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/semantica/benchmarks/README.md#L1-L12>
  - performance test：<https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/tests/vector_store/test_performance_benchmarks.py#L40-L52>
  - CHANGELOG indexed search：<https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/CHANGELOG.md#L1763>
  - CHANGELOG 137+ benchmarks：<https://github.com/semantica-agi/semantica/blob/3ebdb951df7f3499bd6ffdd2e3c8c27468903b9b/CHANGELOG.md#L2034>

**演示数据**

- CUAD：<https://www.atticusprojectai.org/cuad>（CC BY 4.0）
- ContractNLI：<https://stanfordnlp.github.io/contract-nli/>（CC BY 4.0）
- Synthea：<https://github.com/synthetichealth/synthea>（Apache-2.0）
- MIMIC-IV：<https://physionet.org/content/mimiciv/3.1/>（PhysioNet Credentialed Health Data License 1.5.0）
- SEC Financial Statement Data Sets：<https://www.sec.gov/dera/data/financial-statement-data-sets>（公共领域，需 User-Agent/Fair Access）
- FinQA：<https://github.com/czyssrs/FinQA>（MIT）
- Wikidata dumps：<https://dumps.wikimedia.org/wikidatawiki/entities/>（CC0）
