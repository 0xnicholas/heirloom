# Heirloom

> 轻量级 AI Gateway — 统一多提供商 API，内置智能路由与 MCP 工具集成

## 产品定位

Heirloom 是一个面向生产环境的高性能 AI Gateway，解决以下核心问题：

- **多提供商管理** — 统一 OpenAI 兼容接口，无缝切换 OpenAI、Anthropic、Groq 等后端
- **可靠性保障** — 自动故障转移、指数退避重试、请求队列隔离，确保服务高可用
- **MCP 工具生态** — 集成 Model Context Protocol，让 LLM 自动调用外部工具（文件系统、搜索、API 等）
- **云原生部署** — 单二进制、无状态设计、Docker 原生支持，适合 Kubernetes 集群部署

## 核心能力

### 1. 统一 API 层

提供标准 OpenAI 兼容接口，隐藏底层提供商差异：

| 功能 | 说明 |
|------|------|
| `POST /v1/chat/completions` | Chat Completion（流式 + 非流式） |
| `POST /v1/embeddings` | 文本向量化 |
| `GET /v1/models` | 模型列表聚合 |

**模型选择语法：**
- 隐式 Provider：`"gpt-4"` → 使用默认 provider
- 显式 Provider：`"anthropic/claude-3-opus-20240229"` → 指定路由

### 2. 智能路由与故障转移

**请求流程：**
```
Request → Provider Queue → Key Selection → Retry Policy → LLM API
              ↓                    ↓              ↓
        Per-provider  |   Weighted    |  Exponential
        isolation     |   random      |  backoff
                      |   rotation    |
```

**故障转移触发条件：**
- HTTP 5xx 错误
- HTTP 429 (Rate Limited)
- 网络超时 / 连接失败

**非故障转移错误（立即返回）：**
- HTTP 4xx（除 429 外）
- 认证失败

### 3. Per-Provider 请求队列

每个 Provider 拥有独立的 tokio::sync::mpsc 队列：

- **队列隔离** — 某一 Provider  slowdown 不会级联影响其他 Provider
- **并发控制** — 可配置并发数（默认 100 worker）
- **优雅关闭** — 支持 drain 模式，关闭时返回错误给等待请求

### 4. 动态 Key 选择

参考权重随机算法（整数计算避免浮点精度问题）：

```
Total Weight = Σ(weight × 100)
Random Point = rand[0, Total Weight)
Select Key   = 覆盖 Random Point 的区间
```

**特性：**
- 每次请求独立选择（避免竞争）
- 重试时自动轮换（排除已失败 key）
- Key 池耗尽后重置（循环使用）

### 5. MCP Gateway (v0.2)

支持 Model Context Protocol，让 LLM 自动使用外部工具：

**传输协议：**
| 协议 | 适用场景 | 并发 |
|------|----------|------|
| stdio | 本地 CLI 工具（filesystem、git、sqlite） | 串行 (1) |
| SSE | 远程 MCP 服务 | 并行 (5) |

**Agent 模式：**
1. 自动发现 MCP tools → 注入到 Chat Completion 请求
2. LLM 返回 `tool_calls` → 分类（auto-executable / manual）
3. 并行执行 auto tools → 追加结果到对话历史
4. 继续循环直到 LLM 返回最终响应（最大深度可配置）

**工具命名：** `{clientName}_{toolName}`（如 `filesystem_read_file`）

## 技术架构

### 模块结构

```
src/
├── main.rs              # 入口：配置加载、组件初始化、服务启动
├── config.rs            # TOML + 环境变量配置，启动时验证
├── error.rs             # 结构化错误（含 provider/model/retry_count 上下文）
├── context.rs           # 请求上下文（request_id、fallback_index 追踪）
├── types/               # OpenAI 兼容 Schema（serde 序列化）
├── provider/            # Provider Trait + 实现
│   ├── mod.rs           # Trait 定义（chat/embedding/list_models/stream）
│   ├── openai.rs        # OpenAI API 客户端
│   ├── anthropic.rs     # Anthropic Messages API 适配器
│   └── groq.rs          # Groq（OpenAI 兼容，委托实现）
├── gateway/             # Gateway 核心
│   ├── queue.rs         # ProviderQueue（生命周期管理 + shutdown）
│   ├── retry.rs         # RetryPolicy（指数退避 + jitter）
│   ├── fallback.rs      # FallbackChain（顺序尝试）
│   └── key_selector.rs  # 加权随机选择（整数计算）
├── http/                # HTTP 服务层
│   ├── handlers.rs      # Actix-web 路由处理
│   └── middleware.rs    # 日志、CORS、请求 ID
└── mcp/                 # MCP Gateway (v0.2)
    ├── transport.rs     # JSON-RPC + stdio/SSE 传输
    ├── client.rs        # MCPClientManager（工具发现、并发控制）
    └── agent.rs         # AgentExecutor（多轮 tool calling）
```

### 关键设计决策

#### Provider Trait 设计

```rust
#[async_trait]
pub trait Provider: Send + Sync {
    fn name(&self) -> &'static str;
    async fn chat_completion(&self, req: ChatCompletionRequest) -> Result<...>;
    async fn chat_completion_stream(&self, req: ChatCompletionRequest) -> Result<BoxStream<...>>;
    async fn embedding(&self, req: EmbeddingRequest) -> Result<...>;
    async fn list_models(&self) -> Result<...>;
}
```

- **Trait-based** — 新增 Provider 只需实现 4 个方法
- **Arc<dyn Provider>** — Gateway 层统一处理，无需关心具体实现
- **内部 Key 管理** — Provider 持有 `Vec<WeightedKey>`，每次请求动态选择

#### 错误处理

```rust
pub struct GatewayError {
    kind: ErrorKind,              // Provider / Network / RateLimited / ...
    message: String,
    provider: Option<String>,     // 哪个 Provider 出错
    model: Option<String>,        // 请求哪个模型
    retry_count: Option<u32>,     // 第几次重试
    fallback_index: Option<u32>,  // 第几个 fallback
    status_code: Option<u16>,
}
```

- 自动映射 HTTP 状态码（400/401/429/500/503）
- `is_retryable()` 方法判断是否触发重试
- Tracing span 自动注入上下文

#### 配置验证

启动时严格验证，避免运行时错误：

- 至少一个 Provider 启用
- 每个 Provider 有非空 Key 列表
- Fallback chain 引用的 Provider 存在
- MCP client 的 transport 参数完整

### 性能特性

| 指标 | 设计 |
|------|------|
| **连接池** | reqwest 内置连接池（默认 100 idle / host） |
| **序列化** | serde_json（标准库，非 simd） |
| **异步运行时** | tokio multi-thread（默认 CPU 核心数） |
| **内存分配** | 无对象池（Rust 所有权模型减少 GC 压力） |
| **流式处理** | futures::Stream + SSE 协议 |

## 部署模式

### 单实例（开发/测试）

```bash
heirloom --config config.toml
```

### Docker（生产）

```dockerfile
FROM rust:1.75-slim as builder
WORKDIR /app
COPY . .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates
COPY --from=builder /app/target/release/heirloom /usr/local/bin/
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget --spider http://localhost:8080/health || exit 1
ENTRYPOINT ["heirloom"]
```

### Kubernetes（高可用）

- **无状态设计** — 不依赖本地存储，可多副本部署
- **健康检查** — `/health` endpoint + Kubernetes liveness/readiness probe
- **配置管理** — ConfigMap 挂载 TOML 配置文件

## Roadmap

- [x] v0.1 — Core Gateway（OpenAI/Anthropic/Groq，故障转移，重试）
- [ ] v0.2 — MCP Gateway（stdio/SSE，Agent 模式）
- [ ] v0.3 — 更多提供商（Gemini, Azure, Cohere）
- [ ] v0.4 — Stream Fallback（流式请求故障转移）
- [ ] v0.5 — 可观测性增强（Prometheus 指标，分布式追踪）

## License

MIT
