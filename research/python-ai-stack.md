# Python AI 处理栈选型

> 研究票：#4「研究：Python AI 处理栈选型」  
> 地图：#1「heirloom 架构蓝图：Rust 语义核心 + Python AI 处理层 + TypeScript 工作台」  
> 结论日期：2026-11-15  
> 范围：heirloom v0.1 的 Python 无状态 worker；Rust 持有编排、状态、图与缓存。

## TL;DR

1. **进程边界：sidecar，而不是 PyO3。** Rust 通过本机 Unix domain socket 启动并监督 Python worker；gRPC/protobuf 作为契约。Rust 继续拥有队列、重试、缓存、图状态；Python 只做可替换、可缺席的无状态计算。[S31][S35][S36]
2. **基础 worker 可以很轻。** CPython 3.12 + pypdf/python-docx/BeautifulSoup/lxml/regex/tokenizers/gRPC/OpenTelemetry 的 site-packages 实测 **88 MB**；文档解析四件套的 wheel 下载量只有 **9.5 MB**（6 个包）。
3. **Docling 质量优先、资源代价高。** 官方技术报告（225 页、关闭 OCR）：M3 Max 上 **1.27–2.45 页/秒**，峰值 RSS **2.56–6.20 GB**；Xeon E5-2690 上 **0.60–1.57 页/秒**。适合后台本地摄取，不适合交互式或低内存机器。[S1]
4. **Docling 必须是可选安装。** 默认 `docling` 安装解析出 **103 个包、287 MB wheel**（macOS arm64），其中 torch 是最大单项；模型权重另行下载。[S2][S57]
5. **本地嵌入首选 fastembed + bge-small-en-v1.5。** 52 MB wheel、67 MB 模型、384 维、ONNX Runtime、无 torch；MiniLM-L6-v2 为 90 MB。sentence-transformers 仅在需要 GPU/高质量/自定义模型时启用。[S23]
6. **抽取分两层。** GLiNER（50M/90M/0.3B）负责零样本实体；LLM 负责关系/事件/三元组；spaCy `en_core_web_sm` 做确定性基线。GLiNER 与 Docling 共存时必须把 transformers 锁在 **5.22.x**（GLiNER `<5.23`，Docling `>=5.16`）。[S19][S20][S6]
7. **Unstructured 不做默认解析器。** 它的 README 明确说明默认向 `packages.unstructured.io` 发送轻量遥测（可用 `DO_NOT_TRACK`/`SCARF_NO_ANALYTICS` 关闭）；`[all-docs]` 为 **138 个包、399 MB wheel**，还依赖 tesseract/poppler 等系统库。[S10]
8. **不要 Python 编排器。** Rust 的 SQLite 队列（租约、attempts、退避、幂等键）足够；Celery/Dramatiq/Temporal 都会重复 Rust 已拥有的职责。`diskcache` 最后发布于 2023-08，已停滞；Redis 8 服务端是 RSALv2/SSPLv1/AGPLv3 三许可，不适合塞进本地单二进制。[S41][S42][S43]
9. **可观测性：OTel traces/metrics + structlog JSON logs。** OTel Python 的 traces/metrics 已 Stable，logs 仍为 Development；不要把日志管线押在 OTel logs API 上。[S47]
10. **单二进制体验靠“核心单二进制 + 可选 worker profile”保住。** Docling、torch、Unstructured、sentence-transformers、GLiNER、LiteLLM、Arrow Flight 全部按需安装；核心二进制不依赖 Python 也能完成图/推理/存储操作。

## 1. 结论表：每类首选与备选

| 类别 | 首选 | 备选 | 关键理由 |
|---|---|---|---|
| 进程/服务 | **gRPC over UDS** | FastAPI + Uvicorn（仅调试/远程） | deadline、取消、状态码、health、流式、跨语言 schema 都是现成的；UDS 无端口/无网络暴露。 |
| PDF 解析（快路径） | **pypdf** | — | 纯 Python、0.4 MB、按页文本 + 页码 provenance；无布局/OCR。 |
| PDF/Office 解析（结构） | **Docling**（可选 profile） | Unstructured（不默认） | 布局、阅读顺序、表格结构、DoclingDocument；代价是 torch/模型/2.5–6 GB RSS。 |
| DOCX | **python-docx** | Docling | 轻、直接、段落/表格结构足够 v0.1。 |
| HTML/XML | **BeautifulSoup + lxml** | — | lxml 解析器“非常快”；HTML 语义清洗容易。 |
| 归一化 | **unicodedata + regex** | — | 确定性、零模型、易测试。 |
| 切分/Token 预算 | **HF tokenizers** | tiktoken（仅 OpenAI 模型）；Docling HybridChunker | Rust 实现、本地 `tokenizer.json`、与嵌入模型对齐；Docling 路径可复用结构感知 chunker。 |
| 实体抽取 | **GLiNER S/M** | spaCy `en_core_web_sm` | 零样本标签、CPU 友好、50M/90M 参数；spaCy 做固定标签基线。 |
| 关系/事件/三元组 | **LLM-based extraction**（可选） | GLiNER RelEx / spaCy 依存规则 | LLM 对开放关系最灵活；必须做 JSON Schema 校验和 provenance。 |
| 本地嵌入 | **fastembed**（bge-small-en-v1.5） | sentence-transformers；OpenAI-compatible API | 无 torch、52 MB、67 MB 模型、384 维；质量/GPU 需求再上 ST。 |
| LLM 接入 | **OpenAI-compatible（openai SDK，base_url）** | LiteLLM；厂商原生 SDK | 覆盖 Ollama/vLLM/LM Studio/OpenAI/OpenRouter；LiteLLM 的多 provider 价值用可选 extra 换。 |
| 缓存 | **SQLite（Rust 持有）** | 文件系统内容寻址 blob | WAL 单写多读；无额外服务；>=3.51.3 规避 2026 WAL-reset bug。 |
| 编排/重试 | **Rust SQLite 队列** | Dramatiq（仅当必须 Python 控制面）；Temporal（以后） | Rust 已持有状态；Python 不应再有一份队列。 |
| 可观测性 | **OTel traces/metrics + structlog** | stdlib logging + JSON | traces/metrics Stable；logs 用 structlog，避免 OTel logs 的 Development 状态。 |

## 2. 方法与口径

- **元数据**：版本、许可证、`requires_python`、wheel 大小取自 PyPI JSON API（2026-11-15 查询）。[S54]
- **活跃度**：最后发布时间取 PyPI；star 数、最后 push、仓库许可证取 GitHub REST API（2026-11-15）。[S60]
- **依赖闭包**：用 `uv pip install --dry-run --python-platform aarch64-apple-darwin`（CPython 3.12）解析，统计传递依赖包数与各包 wheel 大小之和。它衡量的是**下载体积**，不是安装后体积，也不含模型权重。[S55]
- **安装体积**：在 macOS x86_64、CPython 3.12 的临时 venv 中实测 `site-packages`；基础 worker 88 MB，fastembed worker 204 MB。Docling 在 x86_64 上因 `docling-parse` 无 macOS x86_64 wheel、源码编译失败而未完成安装（见 §9 风险）。
- **性能**：Docling 的数字来自其官方技术报告 Table 1（2024 年，225 页，OCR 关闭），不是本次实测；当前版本可能不同，落地前应在目标硬件复测。[S1]
- **体积口径**：下表“体积”默认是 **arm64 macOS wheel 下载量 / 传递包数**；x86_64 数字在附录。模型权重单独标注。
- **契约复杂度**：主观评级。低 = 简单请求/响应；中 = 需要 typed schema、批处理或取消；高 = 富文档模型、分布式/工作流语义，或需要大量适配。

### 2.1 Python 版本支持

下表来自各包 PyPI 元数据（2026-11-15）。**交集是 3.11–3.13；v0.1 选 3.12**。

| 候选 | 支持范围 | 候选 | 支持范围 |
|---|---|---|---|
| Docling | 3.10–3.14 | Unstructured | 3.11–3.13 |
| pypdf | 3.10+ | python-docx | 3.9+ |
| BeautifulSoup | 3.7+ | lxml | 3.8+ |
| spaCy | 3.10–3.15 | tiktoken | 3.9+ |
| HF tokenizers | 3.10+ | GLiNER | 3.10+ |
| transformers | 3.11+ | sentence-transformers | 3.10–3.14 |
| fastembed | 3.10–3.14 | onnxruntime | 3.11–3.14 |
| openai | 3.10+ | LiteLLM | 3.10–3.15 |
| FastAPI | 3.11+ | Uvicorn | 3.10+ |
| grpcio | 3.10–3.15 | pyarrow | 3.11–3.15 |
| Ray | 3.10+ | temporalio | 3.11–3.15 |
| Celery | 3.10–3.14 | Dramatiq | 3.10–3.14 |
| OTel Python | 3.10–3.14 | structlog | 3.10–3.15 |
| diskcache | 3+ | redis-py | 3.10+ |

> 3.14 的 free-threaded 构建对 ML wheel 仍不友好；3.13 可以跑，但 Unstructured 已把上限压到 `<3.14`。3.12 是当前 wheel 覆盖与稳定性最好的折中。

## 3. 候选矩阵

### 3.1 文档解析

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **Docling 2.150.1** | 2024-07 起；2026-11-14 发布；70.6k★；仓库当天有推送 [S8] | MIT | **287 MB / 103 包** + 模型权重 | 布局、阅读顺序、表格结构、DoclingDocument；1.27–2.45 页/秒；2.56–6.20 GB RSS；表格 2–6 秒/个；EasyOCR 约 30 秒/页 [S1] | 高（富文档模型，需要归一化；可用导出/序列化 [S5]） | **结构解析首选（可选 profile）** |
| Unstructured 0.27.37 | 2022-09 起；2026-11-06 发布；15.7k★ [S9] | Apache-2.0 | 93 MB / 71 包；`[all-docs]` **399 MB / 138 包** + 系统依赖 | 多格式、fast/hi_res 策略；默认遥测；`[all-docs]` 拉入 whisper、cloud vision、pdf2image 等 [S10] | 中高（element 列表 + 元数据） | 备选；不默认 |
| **pypdf 7.1.0** | 2014 起；2026-11-13 发布；10.3k★ [S11] | BSD-3-Clause | 0.4 MB（四件套合计 9.5 MB / 6 包） | 纯 Python；按页 `extract_text()`；无布局/OCR/阅读顺序 [S11] | 低（页 → 文本） | **PDF 快路径首选** |
| **python-docx 1.2.0** | 2014 起；2025-06 发布；5.8k★ [S12] | MIT | 0.3 MB | 仅 `.docx`（不含 `.doc`）；段落/表格/样式 [S12] | 低 | **DOCX 快路径首选** |
| BeautifulSoup 4.15.0 + lxml 6.1.3 | 2013 / 2007 起；均活跃 | MIT / BSD-3-Clause | 0.1 + 8.6 MB（universal2） | HTML/XML 树；lxml 解析器“非常快” [S13]；需防 XXE/实体膨胀 | 低 | **HTML/XML 首选** |
| （Docling 的 `docling-parse` / `pypdfium2` 后端） | 随 Docling | MIT / BSD 类 | 9.9 MB / 视后端 | `pypdfium2` 更快、内存约减半；`docling-parse` 质量更好 | 低 | Docling 内部选择 |

说明：

- Docling 默认安装 `docling-slim[standard]`，**总是包含 RapidOCR**；如果不需要 OCR，官方建议用 `docling-slim` 的定制 extras 去掉 RapidOCR（但需要自己补 OpenCV）。[S2]
- Docling 的 PDF 模型权重在首次转换时从 Hugging Face 下载；离线部署需用 `artifacts_path` 指向本地权重。[S3]
- Unstructured 的遥测是**默认开启**的，虽然不含文档内容，但对“本地优先、默认不出网”的产品是负面默认值。[S10]
- `lxml` 处理不可信 XML/HTML 时应关闭实体解析与网络访问；Docling 本身依赖 `defusedxml` 做防护，自研快路径也要做同样处理。

### 3.2 归一化与切分

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **unicodedata + regex** | 标准库 + 成熟第三方；稳定 | PSF / Apache-2.0 | < 1 MB | NFKC、空白折叠、标点规范、语言检测前的轻量清洗；确定性、可单测 | 低 | **归一化首选** |
| **HF tokenizers 0.23.3** | 2019 起；2026-10-09 发布；11.2k★ [S18] | Apache-2.0 | 3.1 MB | Rust 实现；本地 `tokenizer.json`；批量编码快；与嵌入模型 tokenizer 对齐 [S18] | 低 | **Token 计数/切分首选** |
| tiktoken 0.14.0 | 2022 起；2026-08-17 发布；19.7k★ [S16] | MIT | 1.1 MB + 编码缓存 | 比同类 tokenizer 快 3–6× [S16]；仅 OpenAI 词表；首次使用会**下载并缓存**编码文件 [S17] | 低中（运行期下载） | 备选；仅 OpenAI 模型预算 |
| spaCy 3.8.18 | 2015 起；2026-10-26 发布；34.1k★ [S14] | MIT | 29 MB / 44 包；模型 sm 12.8 MB / md 33.5 MB / lg 400 MB [S15] | 句子边界、分词、依存、NER；模型体积跨度大 | 中（pipeline/模型） | 备选：句边界/基线 NER |
| Docling HybridChunker | Docling 组件 | MIT | 含在 Docling | 结构层级 + token 感知；自动拆超长、合并同标题短块；表头可重复 | 中 | Docling 路径首选 |

说明：

- Docling 的 HybridChunker 可用 Hugging Face tokenizer 或 tiktoken；它先按文档层级切，再按 token 预算拆分/合并，这正是“实体感知切分”需要的能力。[S4]
- tiktoken 的编码文件来自 `openaipublic.blob.core.windows.net`，并用 `TIKTOKEN_CACHE_DIR`/临时目录缓存；在离线/首次启动场景要预置缓存或改用 HF tokenizer。[S17]
- 切分 tokenizer 必须与嵌入模型 tokenizer 一致；否则“512 token 块”对目标模型可能超限。把 `tokenizer_id` 写进 chunk 元数据。

### 3.3 抽取

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **GLiNER 0.2.30** | 2024-03 起；2026-10-31 发布；4.3k★ | Apache-2.0（代码） | **132 MB / 40 包**（torch 为主）+ 权重 | 零样本 NER；50M/90M/0.3B；CPU 友好；支持 RelEx/流式/PII；权重许可需逐模型核对 | 中（标签、span、offset） | **实体抽取首选** |
| spaCy 3.8 | 同上 | MIT | 29 MB + 13–400 MB 模型 | 固定标签 NER、依存关系、规则；快；可训练 | 中 | 备选：确定性基线/句法关系 |
| transformers 5.23.0 | 2016 起；2026-11-13 发布；169k★ [S21] | Apache-2.0 | 13.3 MB + torch | 任意 token classification/QA/生成模型；灵活但模型特定 | 中高 | 备选：自定义模型 |
| **LLM-based extraction** | 取决于 provider | 取决于 provider/模型 | 0 本地 + 网络 | 开放关系/事件/三元组质量最高；非确定、成本/延迟、需 schema 校验 | 高 | **关系/事件/三元组首选（可选）** |
| GLiNER RelEx / GLiNER2 | 新兴；生态活跃 | 代码 Apache-2.0；模型各异 | 同 GLiNER | 联合实体+关系；比 LLM 轻 | 中 | 观察项；v0.1 不做默认 |

说明：

- GLiNER 论文给出的零样本模型规模是 **S 50M / M 90M / L 0.3B**；fp32 权重大致 0.2/0.36/1.2 GB，量化可显著降低。它明确以 CPU/消费级硬件为目标。[S20]
- 若同时安装 Docling 与 GLiNER：Docling 要求 `transformers>=5.16,<6`，GLiNER 要求 `<5.23`；交集是 **5.16–5.22**，建议锁 `5.22.x`。sentence-transformers 6.3 要求 `>=5.0`，也落在这个区间。[S7][S19][S22]
- LLM 抽取不要直接把自由文本 JSON 塞进图：用 JSON Schema/pydantic 校验，失败时返回 `INVALID_OUTPUT` 并可重试；保留原始响应摘要作为 provenance（注意隐私）。

### 3.4 嵌入

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **fastembed 0.9.1** | 2023 起；2026-10-24 发布；3.3k★ | Apache-2.0 | **52 MB / 32 包** + 67–90 MB/模型 | ONNX Runtime、CPU、无 torch；默认 bge-small-en-v1.5，384 维，67 MB；MiniLM-L6-v2 90 MB；支持 sparse/rerank | 低（模型名、维度、batch） | **本地嵌入首选** |
| sentence-transformers 6.3.0 | 2019 起；2026-11-11 发布；19.3k★ | Apache-2.0 | **169 MB / 45 包**（torch 为主）+ 权重 | 15,000+ 模型；GPU/CPU；质量/任务覆盖最广 | 中（模型加载、设备、batch） | 备选：GPU/高质量/自定义 |
| ONNX Runtime 1.32.0 | 2018 起；2026-11-13 发布；22.3k★ [S25] | MIT | 37 MB | fastembed 的推理引擎；CPU/GPU EP；量化 | 低（引擎层） | fastembed 的运行时，不单独选 |
| OpenAI-compatible embeddings | 成熟 | provider | 0 本地 | 无本地权重；网络；质量取决于模型；批量/限流 | 低中 | 备选：托管/远程 |

说明：

- fastembed 的官方模型表给出 `BAAI/bge-small-en-v1.5` 为 384 维、MIT、0.067 GB；`sentence-transformers/all-MiniLM-L6-v2` 为 384 维、Apache-2.0、0.090 GB。这里的 GB 是模型文件体积，不含运行时。[S23]
- fastembed 的卖点就是“不下载 GB 级 PyTorch、用 ONNX Runtime”，与本地优先目标一致；它的“比 PyTorch 快”是厂商表述，落地前用自有语料 benchmark。[S24]
- `onnxruntime 1.32` 的 macOS arm64 wheel 标签是 `macosx_14_0`；如果 v0.1 还要支持 macOS 13，用 `onnxruntime 1.23.x`（`macosx_13_0`），或把 macOS 14 设为最低版本。
- 嵌入输出建议用 `float32` little-endian `bytes`，不要用 JSON 数组；响应带 `model_id`、`revision`、`dim`、`normalized`、`dtype`。

### 3.5 LLM 接入

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **OpenAI-compatible（openai 3.51.0）** | 2020 起；2026-11-13 发布 | Apache-2.0 | 6.2 MB / 17 包（含 httpx） | `base_url`/`OPENAI_BASE_URL` 指向 Ollama、vLLM、LM Studio、OpenRouter、OpenAI；流式、结构化输出 | 低 | **v0.1 首选** |
| LiteLLM 1.109.1 | 2023 起；2026-11-12 发布；63k★ | MIT（`litellm/enterprise/` 例外） | 76 MB / 60 包 | 多 provider 统一接口、fallbacks、`num_retries`、成本跟踪；依赖面大（含 boto3） | 低中 | 备选：多 provider 路由 |
| Anthropic SDK 2.8.0 | 成熟；2026-11-10 发布 | Apache-2.0 | 2.5 MB | 原生 messages/tool use/prompt caching | 低 | 按需 vendor |
| Google GenAI SDK 3.3.0 | 2025 起；2026-11-12 发布 | Apache-2.0 | 1.4 MB | Gemini 原生能力 | 低 | 按需 vendor |

说明：

- v0.1 的 LLM 支持是**可选能力**：用户配置 endpoint/key 才启用；默认不联网。OpenAI-compatible 一个客户端覆盖绝大多数本地/托管端点，依赖最小。[S26]
- LiteLLM 的价值在“多 provider + fallback + 成本表”，但它的 60 个包和 76 MB 体积不应进入基础 worker；且 Rust 已拥有重试策略，必须把 LiteLLM/OpenAI SDK 的隐藏重试设为 0，避免双重重试。[S27][S28]
- LiteLLM 的许可证是 MIT，但 `litellm/enterprise/` 目录走企业许可；分发时要保留声明并避免把 enterprise 代码打进 wheel。[S27]

### 3.6 工作进程 / 服务

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **gRPC 1.85.1 + protobuf** | 2015 起；2026-10-30 发布；45k★ | Apache-2.0 | 13.1 MB / 3 包 | HTTP/2、双向流、deadline、取消传播、标准状态码、health、metadata；官方 Python UDS 示例 | 中（proto/codegen） | **首选** |
| FastAPI 0.163 + Uvicorn 0.54 | 2018 起；活跃；104.7k★ [S29] | MIT / BSD-3 | 3.4 MB / 14 包 | HTTP/1.1 JSON 简单；无内置队列/背压；`--limit-concurrency` 直接 503，不排队 [S30] | 低（但语义自建） | 备选：调试/远程模式 |
| Arrow Flight（pyarrow 26.0.1） | 2017 起；活跃；17.3k★ | Apache-2.0 | 37.3 MB | 列式 record batch 流；DoGet/DoPut/DoExchange；适合批量向量/表格 | 中高 | 以后：批量嵌入 |
| Ray 2.60.0 | 2017 起；活跃；44.3k★ [S59] | Apache-2.0 | 70.3 MB / 18 包 + 集群进程 | 分布式运行时、调度、dashboard；对单机本地进程过重 | 高 | 不用 |
| Temporal（Python SDK 1.36.1 + Service） | 2023 SDK；活跃 | MIT | 14.3 MB / 5 包 + Server/持久化 | durable workflow、活动重试、版本化；需要独立服务 | 高 | 不用（v0.1） |

说明：

- gRPC 的取消语义、deadline 传播、状态码、health checking 都是标准化的，正好覆盖“最小可靠契约”的大部分需求。[S31][S32][S33][S34]
- gRPC 的流控只对 streaming RPC 有意义；unary 调用需要在应用层自己做 in-flight 限制。[S37]
- gRPC 默认接收消息上限是 **4 MiB**（`GRPC_DEFAULT_MAX_RECV_MESSAGE_LENGTH`）。PDF/解析结果很容易超过，所以契约用“文件路径输入 + 流式/分块输出”，而不是把整个 PDF 塞进 message。[S56]
- Unix domain socket 是 gRPC 官方支持的；Python 示例同时支持 `unix:path` 与 `unix:///absolute_path`。[S36]
- FastAPI/Uvicorn 适合把 worker 暴露给调试工具或远程调用；本地主路径不要用它，因为它没有内建背压，超限直接 503。[S30]

### 3.7 缓存

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **SQLite（Rust 持有）** | 2000 起；极稳定 | Public domain | 0（Rust 内置/绑定） | WAL：读不阻塞写、写不阻塞读；单写者；同主机；适合内容寻址缓存、队列、provenance | 低 | **首选** |
| diskcache 5.6.3 | 2016 起；**最后发布 2023-08**；仓库 2024-08 后基本停更 | Apache-2.0 | ~0.05 MB | 纯 Python、简单 API；无事务/并发保证，维护停滞 | 低 | 不用 |
| Redis 8.x + redis-py 8.1 | 成熟；2026-07-30 发布；13.7k★ [S58] | Server：RSALv2/SSPLv1/AGPLv3；client：MIT [S43] | 0.6 MB client + Server | 多进程/远程缓存；需独立服务、端口、运维 | 中 | 不用（本地单用户） |

说明：

- SQLite 的 WAL 模式允许“多个读者 + 一个写者”并发；所有进程必须在同一主机，正好符合本地优先。[S41]
- **2026 年的 WAL-reset bug** 影响 3.7.0–3.51.2，修复于 **3.51.3**；如果 Rust 侧捆绑 SQLite，必须确认版本 >= 3.51.3，或使用系统已修复版本。[S41]
- 缓存键建议：`sha256(op || model_id || model_revision || options_hash || input_sha256)`；Rust 持有，Python 不持久化业务状态。
- Redis 服务端的 RSALv2/SSPLv1/AGPLv3 与 Apache-2.0 项目的“可自由再分发”目标不兼容；即使只作为可选依赖，也会破坏“单二进制 + 本地优先”。[S43]

### 3.8 编排与重试

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **Rust 自研轻量队列** | 与 heirloom 同生命周期 | 项目许可 | 0 Python | SQLite 表 + 租约 + attempts + 指数退避 + 幂等键 + 死信；与图事务同库 | 中（自研/测试） | **首选** |
| Celery 5.7.0 | 2009 起；2026-11-05 发布；29.2k★ | BSD-3-Clause | 3.0 MB / 16 包 + broker | at-least-once、`acks_late`、prefetch、重试；需要 broker；Python 中心 | 中高 | 不用 |
| Dramatiq 2.2.1 | 2017 起；2026-09-02 发布；5.3k★ | **LGPL-3.0** | 0.1 MB + broker | 比 Celery 简单；Redis/RabbitMQ；需要 broker | 中 | 备选（仅当必须 Python 队列） |
| Temporal 1.36.1 | 2023 SDK；活跃；1.25k★ | MIT | 14.3 MB / 5 包 + Server/DB | durable execution、活动默认重试、workflow versioning；运维重 | 高 | 备选（以后） |

说明：

- Celery 的默认语义是“提前 ack”，只有 `acks_late=True` 才是任务完成后 ack；prefetch 默认是 `worker_prefetch_multiplier × concurrency`，长任务会占住预取槽。这些语义与 Rust 队列的租约模型重复且更难统一。[S44][S45]
- Temporal 的活动默认无限重试（1s 初始、2.0 退避、100s 上限），并支持 non-retryable error；如果未来需要跨进程 durable workflow，它是正确工具，但 v0.1 不需要独立 Server/Persistence。[S39][S40]
- Dramatiq 是 LGPL-3.0；作为独立进程动态使用通常可接受，但分发/静态链接需要法务确认。对一个 Apache-2.0 的单二进制产品，能不引入就不引入。[S46]

### 3.9 可观测性

| 候选（版本） | 成熟度 / 活跃度 | 许可 | 体积（arm64 wheel） | 性能 / 特点 | 契约复杂度 | 结论 |
|---|---|---|---|---|---|---|
| **OpenTelemetry Python 1.45.2** | 2019 起；2026-10-29 发布；2.7k★ | Apache-2.0 | 14.0 MB / 12 包（OTel + OTLP exporter + structlog 合计） | traces/metrics **Stable**；logs **Development**；W3C context propagation；OTLP/Prometheus | 中（SDK/采样/exporter） | **traces/metrics 首选** |
| **structlog 26.1.0** | 2013 起；2026-06-06 发布；5.0k★ [S48] | MIT OR Apache-2.0 | 0.1 MB | 结构化 JSON 日志、stdlib 集成、contextvars；轻 | 低 | **日志首选** |
| stdlib logging + JSON formatter | 标准库 | PSF | 0 | 零依赖；需自己写 formatter/context | 低 | 备选 |
| prometheus-client 0.26.0 | 成熟；2026-07-24 发布 | Apache-2.0 AND BSD-2-Clause | 0.1 MB | `/metrics` 文本端点；简单 | 低 | 可选 |

说明：

- OTel Python 的 traces/metrics 已是 Stable，logs 仍是 Development；因此 v0.1 用 OTel 做 trace/metrics，用 structlog 输出 JSON 日志，通过 `trace_id`/`span_id` 关联，不依赖 OTel logs API。[S47]
- Rust 侧生成 `traceparent`，Python 侧用 OTel propagator 继续 span；日志禁止记录文档正文/实体原文，只记录 `request_id`、`op`、`stage`、`model`、耗时、字节数、outcome。[S53]
- 关键指标：`requests_total{op,outcome}`、`duration_seconds`、`inflight`、`queue_depth`、`rss_bytes`、`pages_total`、`tokens_total`、`retryable_errors_total`。

## 4. 进程边界：sidecar vs PyO3

### 4.1 取舍

| 维度 | PyO3 内嵌 | 同机 sidecar |
|---|---|---|
| 崩溃/OOM 隔离 | **无**：Python/扩展 segfault 或 OOM 会带走 Rust 核心 | **有**：worker 死掉，核心重启/降级 |
| GIL/并行 | Rust 线程必须 attach/detach；C 扩展持有 GIL；free-threaded Python 3.14 才“完全支持”，ML wheel 生态滞后 | 每进程独立 GIL；用多进程/多 worker 扩并行 |
| 打包 | 仍需 CPython + 所有 wheel；不是真正单二进制；ABI 绑死 Python 3.x | Python 运行时 + venv 作为可选组件；核心保持单二进制 |
| 启动/模型加载 | 核心进程内加载；模型常驻，内存无法回收 | worker 可预热/重启/按需启动；模型常驻在独立进程 |
| IPC | 零拷贝可做到（PyO3/Arrow），低延迟 | UDS + protobuf/Arrow；本地延迟足够，需序列化 |
| 版本耦合 | 核心与 Python 代码同版本发布；升级需重编译 | worker 可独立升级；通过 `Hello` 协商 |
| 调试 | 进程内信号/GIL/崩溃难查 | `py-spy`、独立日志、独立重启 |
| 安全/沙箱 | 共享地址空间 | 可单独 sandbox/seccomp/权限 |
| 内存 | 共享地址空间，无额外 Python 运行时 | 多一份 Python 运行时（几十 MB），但模型 RSS 隔离 |
| 开发速度 | Rust + Python 双栈构建 | 纯 Python 迭代，Rust 只改契约 |

**结论：v0.1 用 sidecar。** 理由不是“性能”，而是**故障隔离 + 可选安装 + 独立升级**。Docling 的峰值 RSS 可达 6 GB，且底层是原生 C++/torch；把它嵌进 Rust 核心等于把核心的稳定性押在 Python 扩展上。[S1]

PyO3 仍有位置：如果未来要做“无 daemon 的极简模式”，可以用 PyO3 内嵌**小模型**（tokenizer、fastembed/ONNX 小模型），但不应内嵌 Docling/torch/OCR。PyO3 的 GIL 与 free-threaded Python 细节见官方指南：Rust 线程需要 attach 到解释器，非 Python 工作要 `detach` 才能并行；free-threaded Python 3.13 起可用、3.14 才“fully supported”。[S49][S50]

### 4.2 进程生命周期

- Rust 在应用数据目录创建 `run/worker.sock`（权限 0600），以子进程方式启动 `heirloom-worker`；不监听 TCP，不暴露端口。
- Rust 设置环境：socket 路径、模型缓存目录、日志级别、离线标志、LLM endpoint/key（key 从 OS keychain 读取，不写配置文件）。
- Rust 启动后调用 `grpc.health.v1.Health/Watch` 等待 `SERVING`，再调用 `Hello`；`Hello.ready=false` 表示模型仍在加载。
- worker 崩溃：Rust 以指数退避重启（如 1s/2s/4s，最多 5 次），并把当前请求标记为可重试；连续崩溃则降级为“AI 功能不可用”，核心继续工作。
- 关闭：SIGTERM → `NOT_SERVING` → 等待 in-flight 完成（grace 30s）→ 退出；Rust 超时后 SIGKILL。
- 日志：worker 只写 **stderr** 的 JSON lines；stdout 保留给协议/调试，不写日志。

## 5. 最小可靠进程契约

目标：**Rust 拥有编排与状态；Python 无状态；契约可版本化、可观测、可取消、可背压、可重试。**

### 5.1 传输与会话

- **传输**：gRPC over Unix domain socket；protobuf 二进制。
- **为什么不是 HTTP/JSON**：gRPC 自带 deadline、取消传播、标准状态码、health checking、流式与 metadata；Rust 侧 tonic 与 Python 侧 grpcio 都是一等公民。[S31][S32][S33][S34]
- **为什么不是 Arrow Flight**：Flight 适合列式批量数据；v0.1 的文档/切片/实体是 JSON 级负载，pyarrow 的 37 MB 和 Arrow schema 复杂度不值得。批量嵌入变大后再加 Flight 作为可选数据面。[S38]
- **消息大小**：gRPC 默认接收上限 4 MiB；大输入用 `input_uri`（Rust 临时目录中的文件路径），大输出用 `ExecuteStream` 分块。不要简单把上限调到 1 GB，否则内存不可控。[S56]

### 5.2 Schema

最小实现只需要 `Hello` + `Execute` + 标准 `grpc.health.v1.Health`；`ExecuteStream` 用于大结果/进度，`Shutdown` 用于优雅退出。下面的 proto 是建议的完整形态（省略 `Item`/`ItemResult`/`Usage`/`Outcome` 等辅助 message；实际文件放 `proto/heirloom/worker/v1/worker.proto`）：

```proto
syntax = "proto3";
package heirloom.worker.v1;

service Worker {
  rpc Hello(HelloRequest) returns (HelloResponse);
  rpc Execute(ExecuteRequest) returns (ExecuteResponse);
  rpc ExecuteStream(ExecuteRequest) returns (stream ExecuteEvent);
  rpc Shutdown(ShutdownRequest) returns (ShutdownResponse);
}

message HelloRequest {
  string protocol_version = 1;   // semver, e.g. "1.0.0"
  string client_version = 2;     // heirloom core version
  repeated string required_ops = 3;
}

message HelloResponse {
  string protocol_version = 1;
  string worker_version = 2;
  string python_version = 3;
  string contract_hash = 4;      // sha256(FileDescriptorSet)
  repeated OpCapability ops = 5;
  repeated ModelInfo models = 6;
  Limits limits = 7;
  bool ready = 8;                // false while warming models
}

message ExecuteRequest {
  string request_id = 1;         // ULID/UUIDv7; idempotency key
  string op = 2;                 // parse|chunk|extract|embed
  string traceparent = 3;        // W3C trace context
  int64 deadline_unix_ms = 4;    // also set gRPC deadline
  string input_uri = 5;          // file path in Rust-owned temp dir
  bytes input = 6;               // small inline payload
  string input_sha256 = 7;
  string options_json = 8;       // op-specific, schema-versioned
  repeated Item items = 9;       // batch for chunk/extract/embed
  string result_schema = 10;     // e.g. "parsed_document/v1"
}

message ExecuteResponse {
  string request_id = 1;
  Outcome outcome = 2;           // OK|PARTIAL|ERROR
  bytes result = 3;              // JSON bytes per result_schema
  Error error = 4;
  repeated ItemResult items = 5;
  Usage usage = 6;               // pages, tokens, bytes, rss, wall_ms
  string worker_version = 7;
  map<string,string> model_versions = 8;
}

message Error {
  string code = 1;               // canonical gRPC code, e.g. "RESOURCE_EXHAUSTED"
  string reason = 2;             // stable string, e.g. "OOM"
  bool retryable = 3;
  string stage = 4;              // parse|layout|ocr|table|extract|embed
  string message = 5;
  map<string,string> details = 6;
  int32 retry_after_ms = 7;
}
```

结果 schema（JSON，`result_schema` 指定）：

- `parsed_document/v1`：`pages[]`（页码、宽高）、`blocks[]`（`id`、`type`、`text`、`page_no`、`bbox`、`reading_order`、`parent_id`、`style`）、`metadata`、`provenance`（parser、version、model_versions）。
- `chunks/v1`：`chunks[]`（`id`、`text`、`token_count`、`page_no`、`bbox`、`headings`、`source_block_ids`）、`tokenizer`（`id`、`version`）。
- `entities/v1`：`entities[]`（`id`、`type`、`text`、`start`、`end`、`score`、`chunk_id`、`page_no`、`bbox`）、`model`（`id`、`revision`）。
- `relations/v1`：`relations[]`（`subject_id`、`predicate`、`object_id`、`confidence`、`evidence_chunk_ids`）。
- `embeddings/v1`：`model`、`dim`、`dtype`（`float32`）、`normalized`、`vectors`（连续 little-endian bytes）。

**Offset 约定**：文本统一 UTF-8；实体 span 用 **UTF-8 字节偏移**（Rust 原生），Python 侧在返回前从码点转换。必须在契约里写死，否则跨语言会错位。

**`input_uri` 安全**：只允许 Rust 生成的、位于本次任务临时目录内的路径；worker 用 `realpath` 校验前缀，拒绝符号链接逃逸；Rust 负责创建/清理，worker 只读。这样既绕开 4 MiB 限制，也不把任意文件读取权交给 worker。

**二进制结果**：`embeddings` 不要塞进 JSON（base64 会膨胀 33%）。`result_schema=embeddings/v1` 时，`result` 使用独立的 protobuf `Embeddings` message（`bytes` 连续 float32）或 raw bytes；JSON 仅用于调试模式。

### 5.3 批处理

- **Parse**：一次一个文档。Docling 的批处理可能放大内存，且取消粒度差；Rust 用并发 1 的专用 worker。
- **Chunk**：可按文档批量（`items`），每项独立结果；单文档失败不影响其他。
- **Extract**：按 chunk/句子批量；GLiNER 可 batch，LLM 抽取按 token 预算分组。
- **Embed**：`items` 批量，受 `max_batch_items`（建议 64）和 `max_batch_bytes`（建议 2 MB）双限制；返回逐项向量与 usage。
- **部分成功**：`outcome=PARTIAL`，`items[]` 每项带自己的 `outcome`/`error`；Rust 按项重试，不重跑整批。

### 5.4 背压

- worker 在 `Hello.limits` 公布每 op 的 `max_inflight`：parse=1、extract=1–2、embed=2–4；以及 `max_queue`。
- Rust 为每个 worker/op 建信号量，**永远不超过** `max_inflight`；队列满时不发送，先排队。
- worker 内部用有界队列；满时立即返回 `RESOURCE_EXHAUSTED` + `retry_after_ms`，不无限排队。
- gRPC `maximum_concurrent_rpcs` 作为第二道闸；流式场景依赖 HTTP/2 flow control，unary 场景不要指望它。[S37]
- 内存感知：worker 在 `usage.rss_bytes` 上报；Rust 对 parse worker 设 RSS 上限（如 8 GB），超限杀进程并标记 `OOM`（可重试/降批）。
- 不要用“把并发调大”解决吞吐；Docling 的单 worker 已经能吃掉数 GB。

### 5.5 取消

- Rust 为每次调用设置 gRPC deadline，并在 `ExecuteRequest.deadline_unix_ms` 冗余传递；worker 用 `context.time_remaining()` 决定是否继续。[S33][S35]
- worker 在**页/块/批次边界**检查 `context.is_active()`；不可中断的原生调用（Docling 整文档转换、OCR）只能靠**杀进程**硬取消。
- 硬取消流程：Rust 取消 RPC → 等待短 grace（如 2s）→ SIGTERM → 再等 2s → SIGKILL → 重启 worker；请求标记 `CANCELLED`，不重试。
- LLM/HTTP 调用用 httpx 的超时与取消；不要让 worker 在取消后继续写结果。
- 取消是**协作式**的；契约必须明确“不保证立即停止原生解析”，否则调用方会误以为取消是即时的。

### 5.6 错误分类

使用 gRPC canonical code + 稳定 `reason` 字符串；`retryable` 是权威标志。

| gRPC code | reason 示例 | retryable | Rust 动作 |
|---|---|---|---|
| `INVALID_ARGUMENT` | `INVALID_INPUT`、`UNSUPPORTED_FORMAT`、`BAD_OPTIONS` | 否 | 修请求/换 parser；记录 |
| `FAILED_PRECONDITION` | `MODEL_MISSING`、`OPTIONAL_DEP_MISSING`、`CONTRACT_MISMATCH`、`ENCRYPTED_PDF` | 否 | 安装 profile/升级/提示用户解密 |
| `RESOURCE_EXHAUSTED` | `OOM`、`QUEUE_FULL`、`MODEL_BUSY` | 是 | 退避、降 batch/并发、换 worker |
| `UNAVAILABLE` | `WORKER_RESTARTING`、`WORKER_BUSY` | 是 | 重启/路由；指数退避 |
| `DEADLINE_EXCEEDED` | `TIMEOUT` | 是（幂等时） | 增大 deadline 重试一次；仍失败则死信 |
| `CANCELLED` | `CLIENT_CANCELLED` | 否 | 停止，不重试 |
| `INTERNAL` | `WORKER_BUG` | 一次 | 重试一次；仍失败则死信并上报 |
| `UNIMPLEMENTED` | `OP_NOT_SUPPORTED` | 否 | 路由到具备该 op 的 worker |
| `DATA_LOSS` | `CORRUPT_OUTPUT` | 否 | 隔离输入，记录 provenance，提示用户 |
| `ABORTED` | `CONFLICT` | 是（更高层） | 重新读取状态后重试 |

规则：

- Python **不内部重试**（包括 OpenAI/LiteLLM SDK 的 `num_retries` 设为 0）；重试只发生在 Rust，避免放大和重复计费。
- 每个错误带 `stage`，便于区分“解析失败”与“抽取失败”；指标按 `reason` 聚合。
- 不要把所有异常都映射成 `INTERNAL`；模型缺失/格式不支持是用户可修复的 `FAILED_PRECONDITION`/`INVALID_ARGUMENT`。

### 5.7 版本协商与演进

- `protocol_version` 用 SemVer；Rust 要求 **major 相同**；minor 向后兼容，patch 只修 bug。[S52]
- `contract_hash` 是 `FileDescriptorSet` 的 SHA-256；Rust/Python 生成代码不一致时立即 `FAILED_PRECONDITION`，不要“带病运行”。
- `Hello.ops` 列出支持的 op 与输入格式；`Hello.models` 列出模型 id/revision/hash/license；Rust 只发能力内的请求。
- `Hello.ready` 区分“进程活着”和“模型可用”；模型加载期间 `Health=NOT_SERVING`。[S34]
- proto 演进：只做 additive 变更；不复用 field number；删除字段用 `reserved`；wire 用二进制（proto3 保留 unknown fields，JSON 会丢）。[S51]
- `worker_version`、`python_version`、`model_versions` 写入每次响应；Rust 记录到 provenance（PROV-O 的 `used`/`wasGeneratedBy`），保证审计可追溯。

### 5.8 幂等、健康、关闭、观测

- **幂等**：`request_id` 是幂等键；Rust 在队列里去重；重试复用同一个 `request_id`，只增加 `attempt`。Python 无状态，不做去重。
- **健康**：标准 `grpc.health.v1.Health`（`SERVING`/`NOT_SERVING`）+ `Watch`；不要在业务 RPC 里做“心跳”。
- **关闭**：SIGTERM → `NOT_SERVING` → drain grace → 退出；Python 侧对应 `server.stop(grace)`，Rust 侧超时后强杀。[S34]
- **观测**：`traceparent` 放在请求字段与 gRPC metadata；Python 建 span；日志 JSON 到 stderr；指标按 op/outcome/reason 维度。
- **安全**：UDS 权限 0600；不监听 TCP；worker 以当前用户运行；如未来支持远程，再引入 mTLS/token，不要现在放宽。

## 6. Docling 的本地优先适用性

### 6.1 官方数字

Docling Technical Report 的 Table 1：225 页测试集，**OCR 关闭**，标准模型管线。[S1]

| 硬件 | 后端 | 线程 | TTS | 页/秒 | 峰值 RSS |
|---|---|---:|---:|---:|---:|
| Apple M3 Max（16 核） | docling-parse | 4 | 177 s | 1.27 | 6.20 GB |
| Apple M3 Max | docling-parse | 16 | 167 s | 1.34 | — |
| Apple M3 Max | pypdfium | 4 | 103 s | 2.18 | 2.56 GB |
| Apple M3 Max | pypdfium | 16 | 92 s | 2.45 | — |
| Intel Xeon E5-2690（16 核） | docling-parse | 4 | 375 s | 0.60 | 6.16 GB |
| Intel Xeon E5-2690 | docling-parse | 16 | 244 s | 0.92 | — |
| Intel Xeon E5-2690 | pypdfium | 4 | 239 s | 0.94 | 2.42 GB |
| Intel Xeon E5-2690 | pypdfium | 16 | 143 s | 1.57 | — |

另外：表格结构识别约 **2–6 秒/个表**；EasyOCR 在 CPU 上“可达 30 秒/页”。[S1]

### 6.2 判断

**适合“后台本地摄取”，不适合“交互式/低内存”。**

- 单 worker 吞吐约 **1–2.5 页/秒**（M3 Max）；100 页文档约 40–80 秒。对“导入后后台处理”可接受，对“打开即预览”不可接受。
- 峰值 RSS **2.5–6.2 GB**；在 8 GB 机器上跑一个 Docling worker 已接近极限。必须并发 1，并让 Rust 做内存上限与降级。
- `pypdfium` 后端更快、内存约减半，但表格结构质量下降；本地优先默认选它，质量不够再切 `docling-parse`。
- OCR 默认关闭；扫描件需要 OCR 时，优先 RapidOCR/ONNX 或 macOS Vision，不要默认 EasyOCR（30 秒/页）。
- 模型权重首次下载；离线部署用 `artifacts_path`；安装体积 287 MB wheel（arm64）+ 权重，必须可选。[S2][S3]
- Docling 的 VLM pipeline（Granite-Docling-258M、SmolDocling-256M 等）不在 v0.1 默认路径；258M 参数已比标准布局/表格模型更重，12B 级 VLM 更不适合本地默认。[S6]

**推荐策略**：先用 pypdf 快路径；只有检测到多栏/表格/阅读顺序问题时才路由到 Docling；Docling worker 独立进程、并发 1、pypdfium 后端、OCR off、RSS 上限；失败/超时降级为 pypdf 文本 + 警告。

## 7. 单二进制与安装分层

### 7.1 哪些东西会破坏“单二进制 + 本地优先”

| 组件 | 增量体积（下载） | 为什么破坏体验 | 打包决策 |
|---|---:|---|---|
| CPython 运行时 + venv | 数十 MB + site-packages | 不是单个可执行文件；需要解释器/标准库/ABI | 核心不嵌入；worker 作为可选组件 |
| **torch** | 80 MB（macOS arm64）– 530 MB（Linux x86_64） | 原生库、GPU/CPU 变体、平台 wheel；启动/内存大 | 仅 `docling`/`extract`/`st` profile |
| **Docling 默认** | **287 MB / 103 包**（arm64）+ 权重 | 2.5–6.2 GB RSS；模型下载；x86_64 macOS 无 wheel | 可选 `docling` profile；并发 1 |
| Unstructured `[all-docs]` | **399 MB / 138 包** + 系统依赖 | tesseract/poppler、whisper、cloud vision；默认遥测 | 不默认；如用则锁 extras、关遥测 |
| sentence-transformers | **169 MB / 45 包** | torch；模型权重；GPU 变体 | 可选 `embed-gpu`/`quality` profile |
| GLiNER | **132 MB / 40 包** | torch；权重 0.2–1.2 GB | 可选 `extract` profile |
| spaCy lg 模型 | 400 MB | 单模型就 400 MB | 只装 sm（12.8 MB），lg 按需 |
| fastembed | **52 MB / 32 包** + 67–90 MB/模型 | 无 torch，但仍需 Python/ONNX/model | 可作 `embed` 标准 profile；不进核心二进制 |
| LiteLLM | **76 MB / 60 包** | 依赖面大（boto3 等）；仅联网时需要 | 可选 `llm` profile |
| Arrow Flight / pyarrow | **37 MB** | 只为列式批量；v0.1 不需要 | 可选 `flight` profile（以后） |
| Redis / Temporal / Ray | 独立服务 | 端口、进程、持久化、运维；不是单二进制 | 永不捆绑 |
| OCR（EasyOCR/Tesseract） | 权重 + 系统库 | 平台依赖、模型下载、慢 | 可选 `ocr` profile |

注意：上表以 macOS arm64 为基准；Linux 上 torch 还会拉入 `nvidia-cu*` 依赖，实际安装可达数 GB。Linux CPU-only 部署应使用 PyTorch CPU index（Docling 安装文档给了 `--extra-index-url https://download.pytorch.org/whl/cpu` 的示例），否则“本地优先”会变成“本地显卡依赖”。[S2]

### 7.2 推荐 profile

| Profile | 内容 | 体积（参考） | 用途 |
|---|---|---:|---|
| **core（Rust）** | 图/存储/推理/REST/MCP/CLI | 单二进制 | 无 AI 也可用 |
| **worker-base** | CPython 3.12 + pypdf、python-docx、bs4/lxml、regex、tokenizers、gRPC、structlog、OTel | site-packages **约 90 MB**（x86_64 实测 88 MB） | 文本型 PDF/DOCX/HTML、切分、协议 |
| **worker-embed** | base + fastembed + bge-small 权重 | +52 MB wheel +67 MB model（site-packages 204 MB） | 本地嵌入 |
| **worker-docling** | base + Docling（pypdfium，OCR off） | +287 MB wheel + 权重 | 结构解析；并发 1 |
| **worker-extract** | base + GLiNER S/M（或 spaCy sm） | +132 MB wheel + 权重 | 零样本实体 |
| **worker-llm** | base + openai（+ 可选 LiteLLM） | +6–76 MB | 关系/三元组/事件 |
| **worker-full** | 以上全部 | 很大 | 开发/演示，不作为默认 |

安装 UX：`heirloom worker install --profile docling` 下载签名 wheel + 模型 manifest，校验 SHA-256，落到应用数据目录；离线用 `--from-dir`。核心二进制只负责发现/启动/健康检查，不把 Python 依赖编译进去。

## 8. 推荐 v0.1 栈

### 8.1 选型

| 能力 | 首选 | 备选 | 备注 |
|---|---|---|---|
| Python | **CPython 3.12.x** | 3.11 / 3.13 | Unstructured 上限 `<3.14`；3.12 的 wheel 覆盖最稳 |
| RPC | **gRPC 1.85 + protobuf 7 over UDS** | FastAPI/Uvicorn | UDS 权限 0600；不监听 TCP |
| PDF 快路径 | **pypdf 7.1** | — | 按页文本 + 页码 |
| PDF 结构 | **Docling 2.150（可选）** | Unstructured | pypdfium、OCR off、并发 1、RSS 上限 |
| DOCX | **python-docx 1.2** | Docling | `.docx` only |
| HTML/XML | **BeautifulSoup 4.15 + lxml 6.1** | — | 关闭实体解析/网络 |
| 归一化 | **unicodedata + regex** | — | NFKC + 空白/标点规范 |
| 切分 | **HF tokenizers 0.23** | tiktoken（OpenAI）；Docling HybridChunker | 与嵌入 tokenizer 对齐 |
| 实体 | **GLiNER 0.2.30（S/M）** | spaCy `en_core_web_sm` | 零样本标签 |
| 关系/事件/三元组 | **LLM（openai SDK，base_url）** | GLiNER RelEx/规则 | JSON Schema 校验 |
| 本地嵌入 | **fastembed 0.9.1 + bge-small-en-v1.5** | sentence-transformers 6.3 | 384 维、67 MB |
| 远程嵌入 | OpenAI-compatible | — | 可选 |
| LLM 接入 | **openai 3.51（OpenAI-compatible）** | LiteLLM；vendor SDKs | 关隐藏重试 |
| 缓存 | **SQLite（Rust 持有）** | 文件系统 blob | >=3.51.3 |
| 编排 | **Rust SQLite 队列** | Dramatiq/Temporal（以后） | 租约/退避/幂等/死信 |
| 可观测性 | **OTel 1.45 traces/metrics + structlog 26.1** | stdlib logging | logs 走 structlog |
| 打包 | **`heirloom-worker` profiles** | full | 签名 manifest |

### 8.2 版本锁定建议

用 lockfile 锁死，不在代码里写死 patch；以下为兼容区间：

```text
python                 3.12.*
grpcio                 1.85.*
protobuf               7.*
pypdf                  7.1.*
python-docx            1.2.*
beautifulsoup4         4.15.*
lxml                   6.1.*
regex                  2026.*
tokenizers             0.23.*
fastembed              0.9.*
onnxruntime            1.23.*        # macOS 13；1.32.* 需 macOS 14+
spacy                  3.8.*
en-core-web-sm         3.8.0
gliner                 0.2.*
transformers           5.22.*        # GLiNER <5.23 且 Docling >=5.16
torch                  2.11.*        # arm64；Intel macOS 只能 2.2.2 + numpy<2
docling                2.150.*       # 可选
openai                 3.51.*
litellm                1.109.*       # 可选
opentelemetry-sdk      1.45.*
structlog              26.1.*
```

注意：`torch==2.2.2` 是 Docling 在 Intel macOS 的兼容线（Docling FAQ 指出 PyTorch 在 2.2.2 之后不再支持 macOS x86_64）；如果 v0.1 只承诺 Apple Silicon + Linux，可以把 Intel 放到“尽力支持”。[S3]

### 8.3 集成注意事项

1. **一个嵌入模型一个 tokenizer**：chunk 的 `token_count` 必须用该嵌入模型的 tokenizer 计算；把 `tokenizer_id`/`revision` 写进 chunk。
2. **不要泄露 Docling schema**：Python 把 `DoclingDocument` 归一化成 heirloom 的 `parsed_document/v1`；Rust 不依赖 Docling 版本。
3. **关闭遥测**：如果用 Unstructured，启动环境设 `DO_NOT_TRACK=1` 或 `SCARF_NO_ANALYTICS=1`。[S10]
4. **关闭隐藏重试**：OpenAI SDK/LiteLLM 的 `num_retries=0`；Rust 统一重试。
5. **LLM JSON 校验**：JSON Schema + pydantic/msgspec；失败返回 `INVALID_OUTPUT`，最多一次修复重试；记录模型/提示词哈希。
6. **离线**：Docling `artifacts_path`；模型缓存目录显式配置；首次下载需用户同意。Linux CPU-only 安装用 PyTorch CPU index，避免 CUDA 依赖膨胀。[S2]
7. **OCR 默认关**：扫描件走可选 `ocr` profile；优先 RapidOCR/ONNX 或 macOS Vision，避免 EasyOCR 的 30 秒/页。
8. **内存**：parse worker 并发 1；RSS 上限 8 GB；embed batch 32–64；extract batch 按 token 预算。
9. **隐私**：默认不把文档文本发给 LLM；日志不记录正文/实体原文；API key 存 OS keychain。
10. **版本诊断**：`heirloom worker doctor` 输出 `Hello`、模型清单、磁盘占用、Python/torch/Docling 版本，便于支持。

## 9. 风险与未决问题

1. **Docling 性能数字是 2024 年报告**，当前 2.150 的模型/后端可能不同；落地前在目标 M 系列/Linux 机器上用自有语料复测（页/秒、峰值 RSS、表格质量）。
2. **Docling Intel macOS**：`docling-parse 7.26.0` 只有 macOS arm64 wheel，没有 x86_64；本次在 macOS 13.7/CLT 上源码编译失败。若必须支持 Intel Mac，需要固定旧版/补工具链，建议 v0.1 只承诺 Apple Silicon + Linux。[S57]
3. **模型许可未逐一核验**：Hugging Face 在本研究环境不可达；Docling 权重、GLiNER 各模型、BGE/MiniLM 的模型卡许可需在联网环境逐项审计（代码许可不等于权重许可）。
4. **Docling 权重体积未实测**：文档只说首次下载；需要测 `~/.cache` 占用并纳入安装器磁盘预估。
5. **Unstructured 遥测**：即使用户关闭，默认开启的事实对隐私叙事不利；如果选用，必须在启动时强制关闭并写入隐私说明。
6. **LiteLLM 许可/依赖**：MIT 但 enterprise 目录例外；60 个包、76 MB；供应链与版本 churn 风险，必须锁版本 + 哈希。
7. **Redis 服务端许可**：RSALv2/SSPLv1/AGPLv3；不要捆绑；如未来支持多用户，再作为外部依赖。
8. **Dramatiq LGPL-3.0**：动态独立进程通常没问题，但分发条款需法务确认；v0.1 不引入。
9. **gRPC 4 MiB 限制**：所有大输入/输出必须走路径或流式；否则会在 PDF/大表格上踩坑。
10. **Python 3.14 free-threaded**：ML wheel（torch/onnxruntime）尚未全面支持；v0.1 用 3.12 GIL 构建，别追新。
11. **SQLite 版本**：Rust 侧捆绑的 SQLite 必须 >=3.51.3（WAL-reset bug 修复）；否则多连接 checkpoint 有极小概率损坏。
12. **GLiNER/Docling 依赖交集**：transformers 必须锁 5.22.x；如果未来 Docling 提升下限或 GLiNER 提升上限，需要重新解冲突。
13. **LLM 非确定性**：同一输入可能不同输出；缓存键要包含 model/temperature/prompt hash，provenance 要记录模型版本。
14. **取消语义**：Docling 整文档转换不可中断，取消只能杀进程；产品层要接受“取消后 worker 重启”的延迟。
15. **macOS 最低版本**：`docling-parse` 的 arm64 wheel 标签是 `macosx_14_0`，`onnxruntime 1.32` 也是 `macosx_14_0`；如果 v0.1 要覆盖 macOS 13，需要固定 `onnxruntime 1.23.x`，并把 Docling 视为“macOS 14+ 可选能力”。

## 10. 附录 A：测量数据

### A.1 依赖闭包（CPython 3.12，macOS arm64，2026-11-15）

命令：`uv pip install --dry-run --python-platform aarch64-apple-darwin <spec>`；wheel 大小求和，含传递依赖。[S55]

| Profile / spec | 包数 | wheel 下载量 |
|---|---:|---:|
| `docling` | 103 | 287.3 MB |
| `unstructured` | 71 | 93.2 MB |
| `unstructured[all-docs]` | 138 | 398.9 MB |
| `pypdf python-docx beautifulsoup4 lxml` | 6 | 9.5 MB |
| `spacy` | 44 | 28.9 MB |
| `gliner` | 40 | 132.0 MB |
| `sentence-transformers` | 45 | 169.2 MB |
| `fastembed` | 32 | 51.7 MB |
| `onnxruntime` | 9 | 36.8 MB |
| `litellm` | 60 | 76.4 MB |
| `openai httpx` | 17 | 6.2 MB |
| `fastapi uvicorn` | 14 | 3.4 MB |
| `grpcio protobuf` | 3 | 13.1 MB |
| `pyarrow` | 1 | 37.3 MB |
| `temporalio` | 5 | 14.3 MB |
| `ray` | 18 | 70.3 MB |
| `celery redis` | 16 | 3.0 MB |
| `dramatiq` | 1 | 0.1 MB |
| `opentelemetry-sdk opentelemetry-exporter-otlp-proto-grpc structlog` | 12 | 14.0 MB |
| `tokenizers tiktoken regex` | 22 | 11.2 MB |

x86_64 macOS 对照：`docling` 102 包 / 349.8 MB；`unstructured[all-docs]` 138 包 / 473.8 MB；`sentence-transformers` 44 包 / 246.6 MB；`gliner` 39 包 / 206.6 MB；`fastembed` 32 包 / 59.7 MB。

### A.2 安装后体积（macOS x86_64，CPython 3.12）

| venv | 内容 | `site-packages` |
|---|---|---:|
| core | pypdf、python-docx、bs4、lxml、regex、tokenizers、structlog、OTel SDK、grpcio、protobuf | **88 MB** |
| fastembed | fastembed + onnxruntime + numpy 等 | **204 MB** |
| docling | 安装失败（见下） | — |

Docling 安装失败原因：`docling-parse 7.26.0` 没有 macOS x86_64 wheel，回退源码编译时 C++ 标准库报错（`lexicographical_compare_three_way` 等）。这与 Docling FAQ 中“Intel Mac 需要 torch 2.2.2 + numpy<2”的已知摩擦一致。[S3][S57]

### A.3 模型体积

| 模型 | 体积 / 参数 | 来源 |
|---|---|---|
| BAAI/bge-small-en-v1.5 | 0.067 GB，384 维，MIT | fastembed 模型表 [S23] |
| sentence-transformers/all-MiniLM-L6-v2 | 0.090 GB，384 维，Apache-2.0 | fastembed 模型表 [S23] |
| BAAI/bge-base-en-v1.5 | 0.210 GB，768 维，MIT | fastembed 模型表 [S23] |
| spaCy `en_core_web_sm` | 12.8 MB | spaCy model releases [S15] |
| spaCy `en_core_web_md` | 33.5 MB | 同上 |
| spaCy `en_core_web_lg` | 400.7 MB | 同上 |
| GLiNER S/M/L | 50M / 90M / 0.3B 参数（fp32 约 0.2/0.36/1.2 GB） | GLiNER 论文 [S20] |
| Docling 布局/TableFormer/OCR | 文档未给统一数字；首次下载，需实测 | Docling FAQ [S3] |

## 11. 来源

| 编号 | 来源 |
|---|---|
| S1 | Docling Technical Report, arXiv:2408.09869, Table 1 — https://arxiv.org/abs/2408.09869 |
| S2 | Docling Installation — https://docling-project.github.io/docling/getting_started/installation/ |
| S3 | Docling FAQ — https://docling-project.github.io/docling/faq/ |
| S4 | Docling Chunking — https://docling-project.github.io/docling/concepts/chunking/ |
| S5 | Docling Serialization — https://docling-project.github.io/docling/concepts/serialization/ |
| S6 | Docling Model Catalog — https://docling-project.github.io/docling/usage/model_catalog/ |
| S7 | Docling on PyPI — https://pypi.org/project/docling/ |
| S8 | Docling GitHub — https://github.com/docling-project/docling |
| S9 | Unstructured on PyPI — https://pypi.org/project/unstructured/ |
| S10 | Unstructured README（默认遥测与 opt-out） — https://github.com/Unstructured-IO/unstructured |
| S11 | pypdf README — https://github.com/py-pdf/pypdf |
| S12 | python-docx README — https://github.com/python-openxml/python-docx |
| S13 | Beautiful Soup Documentation（parser 对比） — https://www.crummy.com/software/BeautifulSoup/bs4/doc/ |
| S14 | spaCy on PyPI — https://pypi.org/project/spacy/ |
| S15 | spaCy model releases（sm/md/lg 资产大小） — https://github.com/explosion/spacy-models/releases |
| S16 | tiktoken README（性能 3–6×） — https://github.com/openai/tiktoken |
| S17 | tiktoken `load.py`（编码下载与缓存） — https://github.com/openai/tiktoken/blob/main/tiktoken/load.py |
| S18 | Hugging Face tokenizers — https://github.com/huggingface/tokenizers |
| S19 | GLiNER GitHub — https://github.com/urchade/GLiNER |
| S20 | GLiNER paper, arXiv:2311.08526（50M/90M/0.3B） — https://arxiv.org/abs/2311.08526 |
| S21 | transformers on PyPI — https://pypi.org/project/transformers/ |
| S22 | sentence-transformers GitHub — https://github.com/UKPLab/sentence-transformers |
| S23 | FastEmbed Supported Models（体积/维度/许可） — https://qdrant.github.io/fastembed/examples/Supported_Models/ |
| S24 | FastEmbed GitHub — https://github.com/qdrant/fastembed |
| S25 | onnxruntime on PyPI — https://pypi.org/project/onnxruntime/ |
| S26 | openai-python README（`base_url`/`OPENAI_BASE_URL`） — https://github.com/openai/openai-python |
| S27 | LiteLLM LICENSE（MIT + enterprise 例外） — https://github.com/BerriAI/litellm/blob/main/LICENSE |
| S28 | LiteLLM Reliability（retries/fallbacks） — https://docs.litellm.ai/docs/completion/reliable_completions |
| S29 | FastAPI on PyPI — https://pypi.org/project/fastapi/ |
| S30 | Uvicorn Settings（`--limit-concurrency` 503） — https://www.uvicorn.org/settings/ |
| S31 | gRPC Status Codes — https://grpc.io/docs/guides/status-codes/ |
| S32 | gRPC Cancellation — https://grpc.io/docs/guides/cancellation/ |
| S33 | gRPC Deadlines — https://grpc.io/docs/guides/deadlines/ |
| S34 | gRPC Health Checking — https://grpc.io/docs/guides/health-checking/ |
| S35 | gRPC Python API（`RpcContext.is_active`/`time_remaining`） — https://grpc.github.io/grpc/python/grpc.html |
| S36 | gRPC Python UDS 示例 — https://github.com/grpc/grpc/tree/master/examples/python/uds |
| S37 | gRPC Flow Control（仅 streaming） — https://grpc.io/docs/guides/flow-control/ |
| S38 | Arrow Flight Format — https://arrow.apache.org/docs/format/Flight.html |
| S39 | Temporal Retry Policies — https://docs.temporal.io/encyclopedia/retry-policies |
| S40 | Temporal Service（Server + persistence） — https://docs.temporal.io/temporal-service |
| S41 | SQLite WAL（并发、2026 WAL-reset bug、3.51.3 修复） — https://sqlite.org/wal.html |
| S42 | diskcache on PyPI（最后发布 2023-08） — https://pypi.org/project/diskcache/ |
| S43 | Redis 8 LICENSE（RSALv2/SSPLv1/AGPLv3） — https://github.com/redis/redis/blob/8.0/LICENSE.txt |
| S44 | Celery Tasks（ack/acks_late/idempotency） — https://docs.celeryq.dev/en/stable/userguide/tasks.html |
| S45 | Celery Optimizing（prefetch） — https://docs.celeryq.dev/en/stable/userguide/optimizing.html |
| S46 | Dramatiq（LGPL-3.0；Redis/RabbitMQ broker） — https://github.com/Bogdanp/dramatiq |
| S47 | OpenTelemetry Python（traces/metrics Stable，logs Development） — https://opentelemetry.io/docs/languages/python/ |
| S48 | structlog — https://github.com/hynek/structlog |
| S49 | PyO3 Parallelism（GIL、detach、free-threaded 3.14） — https://pyo3.rs/latest/parallelism.html |
| S50 | PyO3 Calling Python from Rust — https://pyo3.rs/latest/python-from-rust.html |
| S51 | Protobuf proto3 Updating（reserved/unknown fields） — https://protobuf.dev/programming-guides/proto3/#updating |
| S52 | Semantic Versioning 2.0.0 — https://semver.org/ |
| S53 | W3C Trace Context — https://www.w3.org/TR/trace-context/ |
| S54 | PyPI JSON API（版本/许可/requires_python/wheel 大小） — https://pypi.org/pypi/<package>/json |
| S55 | uv 文档（`uv pip install --dry-run`/平台解析） — https://docs.astral.sh/uv/ |
| S56 | gRPC 默认接收上限 4 MiB（`GRPC_DEFAULT_MAX_RECV_MESSAGE_LENGTH`） — https://github.com/grpc/grpc/blob/master/include/grpc/impl/grpc_types.h |
| S57 | docling-parse on PyPI（无 macOS x86_64 wheel） — https://pypi.org/project/docling-parse/ |
| S58 | redis-py on PyPI（MIT） — https://pypi.org/project/redis/ |
| S59 | Ray on PyPI（分布式应用 API） — https://pypi.org/project/ray/ |
| S60 | GitHub REST API（stars/last push/license） — https://docs.github.com/en/rest |
