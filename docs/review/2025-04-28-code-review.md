# Heirloom 代码审查报告

## 审查范围
- **审查对象**: Heirloom (Rust AI Gateway)
- **对比基准**: Bifrost (Go AI Gateway, maximhq/bifrost)
- **审查日期**: 2026-04-28
- **代码版本**: main@f4a8f49

---

## 1. 架构设计审查

### 1.1 整体架构对比

**Heirloom (当前)**
```
HTTP Server (actix-web)
    -> Handlers
        -> Gateway
            -> Provider Resolution
            -> Fallback Chain (optional)
            -> ProviderQueue (Semaphore-based)
                -> RetryPolicy
                -> Provider
                    -> HttpClient (reqwest)
```

**Bifrost (参考)**
```
HTTP Transport (fasthttp)
    -> Plugin Pipeline (Pre-hooks)
    -> Bifrost Core
        -> Provider Resolution
        -> ProviderQueue (channel-based with workers)
        -> Key Selection
        -> Provider
            -> Provider-specific HTTP client
    -> Plugin Pipeline (Post-hooks)
    -> Object Pools (sync.Pool)
```

### 1.2 关键架构差异

| 维度 | Heirloom | Bifrost | 评估 |
|------|----------|---------|------|
| **并发模型** | Semaphore + per-request spawn | Channel + worker goroutines | Bifrost 更优，避免频繁 spawn |
| **对象池** | 无 | sync.Pool 预分配 | Bifrost 显著减少 GC 压力 |
| **插件系统** | 无 | 完整的 Pre/Post Hook 管道 | Heirloom 缺失关键扩展点 |
| **请求上下文** | 基础 RequestContext | 丰富的 BifrostContext | Heirloom 过于简单 |
| ** Provider 接口** | 4 个方法 | 15+ 种请求类型 | Heirloom 仅覆盖基础场景 |

---

## 2. 详细代码审查

### 2.1 Provider 层

#### 问题 1: Provider 接口过于简单

**Heirloom** (`src/provider/mod.rs`):
```rust
#[async_trait]
pub trait Provider: Send + Sync {
    fn name(&self) -> &'static str;
    async fn chat_completion(&self, request: ChatCompletionRequest) -> Result<ChatCompletionResponse, GatewayError>;
    async fn chat_completion_stream(&self, request: ChatCompletionRequest) -> Result<BoxStream<...>, GatewayError>;
    async fn embedding(&self, request: EmbeddingRequest) -> Result<EmbeddingResponse, GatewayError>;
    async fn list_models(&self) -> Result<ModelList, GatewayError>;
}
```

**Bifrost** 支持的请求类型:
- ChatCompletion / ChatCompletionStream
- TextCompletion / TextCompletionStream
- Responses / ResponsesStream
- Embedding
- Rerank
- ImageGeneration / ImageEdit
- Speech / Transcription
- OCR
- ListModels / CountTokens

**影响**: 仅支持 Chat/Embedding/ListModels，无法处理多模态、语音、图像等现代 AI 工作流。

#### 问题 2: Provider 创建方式耦合

**Heirloom** (`src/gateway/mod.rs:50-78`):
```rust
let provider: ProviderRef = match name.as_str() {
    "openai" => Arc::new(OpenAIProvider::new(...)?),
    "anthropic" => Arc::new(AnthropicProvider::new(...)?),
    "groq" => Arc::new(GroqProvider::new(...)?),
    _ => { tracing::warn!("Unknown provider: {}, skipping", name); continue; }
};
```

**问题**:
1. **硬编码 Provider 映射**: 新增 Provider 需要修改 Gateway 代码
2. **无法动态注册**: 不支持运行时插件化 Provider
3. **Groq 是 OpenAI 包装器**: `GroqProvider` 仅包装 `OpenAIProvider`，却需要重复传递所有参数

**建议**:
```rust
// 使用工厂模式
pub trait ProviderFactory: Send + Sync {
    fn create(&self, config: ProviderConfig) -> anyhow::Result<Box<dyn Provider>>;
    fn name(&self) -> &'static str;
}

pub struct ProviderRegistry {
    factories: HashMap<String, Box<dyn ProviderFactory>>,
}
```

#### 问题 3: Key 选择器缺少健康检查

**Heirloom** (`src/gateway/key_selector.rs`):
```rust
pub fn select_key(keys: &[WeightedKey]) -> &WeightedKey {
    let total_weight: f64 = keys.iter().map(|k| k.weight).sum();
    let mut rng = rand::thread_rng();
    let mut random = rng.gen::<f64>() * total_weight;
    
    for key in keys {
        random -= key.weight;
        if random <= 0.0 {
            return key;
        }
    }
    &keys[0]
}
```

**问题**: 纯加权随机，不跟踪 key 的健康状态（如 429/401 错误率）。

**Bifrost** 的做法:
- 维护 key 的 `KeyStatus`（健康/不健康）
- 自动排除失败的 key
- 支持 key 级别的熔断

---

### 2.2 Gateway 层

#### 问题 4: Streaming 绕过队列和降级

**Heirloom** (`src/gateway/mod.rs:170-188`):
```rust
pub async fn chat_completion_stream(&self, request: ChatCompletionRequest) -> Result<...> {
    let provider = self.providers.get(&provider_name).ok_or_else(...)?;
    // Direct provider call for streaming (bypasses queue)
    provider.chat_completion_stream(request).await
}
```

**严重问题**:
1. **Streaming 不经过队列**: 无法做并发控制、重试、降级
2. **无 fallback 支持**: streaming 请求失败直接返回错误
3. **与 bifrost 架构冲突**: bifrost 的 streaming 同样走队列+worker 模式

#### 问题 5: 降级链设计缺陷

**Heirloom** (`src/gateway/fallback.rs`):
```rust
pub async fn execute_chat_completion(&self, request: ChatCompletionRequest) -> Result<...> {
    match self.primary.chat_completion(request.clone()).await {
        Ok(response) => return Ok(response),
        Err(e) => {
            if !Self::should_fallback(&e) { return Err(e); }
        }
    }
    for (index, fallback) in self.fallbacks.iter().enumerate() {
        match fallback.chat_completion(request.clone()).await {
            Ok(response) => return Ok(response),
            Err(e) => { ... }
        }
    }
}
```

**问题**:
1. **降级不经过队列**: 直接调用 Provider，跳过并发控制和重试
2. **无请求转换**: 不同 Provider 的模型名称、参数格式可能不同，未做转换
3. **无状态跟踪**: 不记录哪些 Provider 当前健康

**Bifrost** 的做法:
- 降级链中的每个 Provider 都走正常队列
- 支持模型名称映射 (`openai/gpt-4` -> `anthropic/claude-3-opus`)
- 自动根据 Provider 健康状态调整降级顺序

#### 问题 6: Provider 解析逻辑问题

**Heirloom** (`src/gateway/mod.rs:237-245`):
```rust
fn resolve_provider(&self, model: &str) -> String {
    if let Some(pos) = model.find('/') {
        model[..pos].to_string()
    } else if let Some(provider) = self.model_map.get(model) {
        provider.clone()
    } else {
        "openai".to_string()  // Default to openai
    }
}
```

**问题**:
1. **默认硬编码为 openai**: 如果用户未配置 openai，会返回不存在的 Provider
2. **不支持模型版本解析**: `gpt-4-turbo-preview` 不会被识别为 `gpt-4` 系列
3. **model_map 是静态映射**: 不支持动态模型发现

---

### 2.3 Queue 层

#### 问题 7: Semaphore-based Queue 性能问题

**Heirloom** (`src/gateway/queue.rs`):
```rust
pub async fn send(&self, request: GatewayRequest) -> Result<GatewayResponse, GatewayError> {
    let (response_tx, response_rx) = oneshot::channel();
    let permit = self.semaphore.clone().acquire_owned().await?;
    
    let provider = self.provider.clone();
    let retry_policy = ...;
    
    tokio::spawn(async move {
        let response = process_request(&provider, &retry_policy, request).await;
        let _ = response_tx.send(response);
        drop(permit);
    });
    
    response_rx.await.map_err(...)
}
```

**性能问题**:
1. **每次请求 spawn 一个新任务**: 高并发时 tokio runtime 调度开销大
2. **oneshot channel 分配**: 每次请求分配新的 channel
3. **无请求批处理**: 无法合并 embedding 请求等可批处理操作
4. **buffer_size 参数被忽略**: `_buffer_size` 未使用

**Bifrost** 的做法:
- 预创建 worker goroutines
- 使用 channel 分发请求
- 对象池复用 (sync.Pool)
- 支持请求合并 (batching)

#### 问题 8: 关闭机制不完整

**Heirloom**:
```rust
pub async fn shutdown(&self) {
    self.closing.store(true, Ordering::SeqCst);
}
```

仅设置原子标志，不:
- 等待 in-flight 请求完成
- 关闭 channel
- 释放资源

---

### 2.4 Retry 层

#### 问题 9: RetryPolicy 缺少 Provider 级定制

**Heirloom**:
- 每个 ProviderQueue 有独立的 RetryPolicy
- 但所有错误类型使用相同的重试策略

**Bifrost**:
- 支持 Provider 特定的重试配置
- 429 (Rate Limit) 使用更长的退避
- 5xx 使用指数退避
- 401/403 不重试

#### 问题 10: 退避算法简单

**Heirloom** (`src/gateway/retry.rs:68-72`):
```rust
fn calculate_backoff(&self, attempt: u32) -> Duration {
    let base = self.backoff_initial.as_millis() as u64 * 2u64.pow(attempt);
    let capped = std::cmp::min(base, self.backoff_max.as_millis() as u64);
    let jitter = rand::thread_rng().gen_range(0..capped / 10);
    Duration::from_millis(capped + jitter)
}
```

**问题**:
1. `2u64.pow(attempt)` 在 attempt > 63 时 panic（虽然实际不会达到）
2. Jitter 范围太小 (10%)，Bifrost 使用全范围 jitter
3. 不支持自定义退避策略（如线性、固定间隔）

---

### 2.5 HTTP 层

#### 问题 11: SSE Streaming 实现问题

**Heirloom** (`src/http/handlers.rs:55-91`):
```rust
let sse_stream = stream
    .map(|chunk| match chunk {
        Ok(chunk) => {
            let data = serde_json::to_string(&chunk).unwrap();
            Ok::<_, actix_web::Error>(Bytes::from(format!("data: {}\n\n", data)))
        }
        Err(e) => {
            let data = serde_json::json!({"error": e.to_string()}).to_string();
            Ok(Bytes::from(format!("data: {}\n\n", data)))
        }
    })
    .chain(futures::stream::once(async {
        Ok::<_, actix_web::Error>(Bytes::from_static(b"data: [DONE]\n\n"))
    }));
```

**问题**:
1. **错误处理不完善**: 流错误返回 JSON 错误对象，但客户端可能无法正确解析
2. **缺少心跳**: 长时间无数据时，连接可能被中间件关闭
3. **无流控制**: 不处理背压 (backpressure)

#### 问题 12: 缺少中间件管道

**Heirloom**: 仅有 CORS 和 RequestId 中间件

**Bifrost**:
- Authentication (OAuth2, API Key)
- Rate Limiting (per-key, per-model, global)
- Request Validation
- Request/Response Logging
- Metrics Collection
- Plugin Hooks

---

### 2.6 MCP 层

#### 问题 13: MCP 集成不完整

**Heirloom**:
- 仅支持基本的 tool 调用
- Agent 模式简单（单次执行）
- 无 tool 结果缓存
- 不支持 MCP resources/prompts

**Bifrost**:
- 完整的 MCP Manager
- CodeMode (Starlark 脚本执行)
- Tool execution with hooks
- Agent loop with depth control

---

### 2.7 配置管理

#### 问题 14: 配置缺少关键字段

**Heirloom 缺少**:
- Provider 级别的模型映射 (`model_aliases`)
- 请求超时细分（连接超时、读取超时、写入超时）
- 健康检查配置
- 熔断器配置
- 缓存配置
- 指标/日志配置

---

## 3. 与 Bifrost 的功能差距

### 3.1 核心功能缺失

| 功能 | Heirloom | Bifrost | 优先级 |
|------|----------|---------|--------|
| **多模态支持** | ❌ | ✅ | 高 |
| **语音/图像 API** | ❌ | ✅ | 中 |
| **Rerank** | ❌ | ✅ | 中 |
| **语义缓存** | ❌ | ✅ | 高 |
| **请求批处理** | ❌ | ✅ | 高 |
| **插件系统** | ❌ | ✅ | 高 |
| **预算管理** | ❌ | ✅ | 低 |
| **Vault 集成** | ❌ | ✅ | 低 |
| **集群模式** | ❌ | ✅ | 低 |

### 3.2 性能差距

Bifrost 声称的性能指标:
- 5k RPS 下 < 100µs 延迟
- 对象池减少 GC 压力
- fasthttp 比 actix-web 更轻量

Heirloom 当前:
- 每次请求 spawn 新任务
- 无对象池
- 使用较重的 actix-web

---

## 4. 代码质量问题

### 4.1 编译警告

当前有 34 个编译警告，主要包括:
- 未使用的字段/方法
- 未使用的 import
- 未使用的变量
- 不规范的命名 (`inputSchema`, `isError`)

### 4.2 错误处理

**不一致**:
- `GatewayError` 有时携带 status_code，有时不携带
- Provider 错误和 Gateway 错误混用
- 缺少错误链 (error chain)

### 4.3 测试覆盖

- 仅 35 个测试（29 单元 + 6 集成）
- 缺少:
  - 并发测试
  - 降级测试
  - Streaming 测试
  - 故障注入测试

---

## 5. 安全审查

### 5.1 API Key 管理

**Heirloom**:
- 使用 `SecretString` 包装 ✅
- 但 key 在内存中以明文存储
- 无 key 轮换机制

### 5.2 请求验证

- 无输入验证（最大消息长度、消息数量限制）
- 无提示词注入防护
- 无输出过滤

---

## 6. 改进建议

### 6.1 短期（1-2 周）

1. **修复 Streaming 降级支持**
   - 让 streaming 走队列
   - 添加 fallback 支持

2. **修复 Provider 解析**
   - 移除硬编码默认值
   - 添加模型别名支持

3. **完善错误处理**
   - 统一错误类型
   - 添加错误链

4. **减少编译警告**
   - 删除未使用的代码
   - 修复命名规范

### 6.2 中期（1 个月）

1. **重构 Queue 层**
   - 使用 worker 模式替代 spawn-per-request
   - 添加对象池

2. **添加插件系统**
   - Pre/Post Hook 接口
   - 中间件管道

3. **完善 MCP**
   - 支持 resources/prompts
   - 添加 tool 结果缓存

4. **增加测试覆盖**
   - 并发测试
   - 故障注入测试

### 6.3 长期（3 个月）

1. **多模态支持**
2. **语义缓存**
3. **请求批处理**
4. **健康检查与熔断**
5. **性能优化**（对象池、连接池）

---

## 7. 总结

### 当前状态: **Alpha / 原型阶段**

Heirloom 实现了 AI Gateway 的基础骨架，但:
- **核心功能不完整**: 仅支持 Chat/Embedding，缺少多模态、缓存、批处理
- **架构可扩展性差**: Provider 硬编码、无插件系统
- **性能未优化**: 无对象池、每次请求 spawn
- **生产就绪度低**: 缺少健康检查、熔断、完善的监控

### 与 Bifrost 的差距

Bifrost 是一个成熟的企业级产品，Heirloom 目前仅实现了其约 **20-30%** 的核心功能。主要差距在:
1. 多 Provider 支持（15+ vs 3）
2. 请求类型覆盖（15+ vs 4）
3. 插件与扩展性
4. 性能优化
5. 企业功能（治理、预算、SSO）

### 建议

如果目标是 "精简但健壮" 的替代方案，建议:
1. **优先完善核心架构**（Queue、Provider Factory、Plugin）
2. **保持精简**: 不追求 Bifrost 的全部功能
3. **专注稳定性**: 完善的测试、监控、降级
4. **文档优先**: 清晰的架构文档和使用指南

---

## 附录 A: 深入审查详细发现

基于对 `Cargo.toml`, `error.rs`, `client/mod.rs`, `http/middleware.rs`, `mcp/agent.rs`, `mcp/transport.rs`, `types/common.rs`, `types/embedding.rs` 的深入审查，补充以下问题：

### A.1 依赖管理问题

#### A.1.1 未使用的依赖 (`Cargo.toml:22`)
```toml
config = "0.14"  # 未在项目中使用
```
**影响**: 增加编译时间和二进制体积。
**建议**: 移除或替换为实际使用的配置库。

#### A.1.2 缺失关键依赖
- **`dashmap`**: 需要并发 HashMap 来替代 `RwLock<HashMap>`（MCPClientManager）
- **`metrics`**: 需要指标收集框架
- **`parking_lot`**: 比标准库锁更快，特别是 `RwLock`

### A.2 MCP 层深入问题

#### A.2.1 Agent 的 `build_pending_response` 为空实现 (`mcp/agent.rs:126-134`)
```rust
fn build_pending_response(
    &self,
    response: ChatCompletionResponse,
    _manual_tools: Vec<ToolCall>,  // 参数被忽略！
) -> ChatCompletionResponse {
    response  // 直接返回，未处理 manual tools
}
```
**问题**: 
- `manual_tools` 参数被忽略
- 没有将 manual tools 标记为 pending 状态
- 客户端无法区分 auto 和 manual tool calls

**Bifrost 的做法**:
- 返回特殊的响应格式，包含 `pending_tool_calls` 字段
- 客户端需要再次调用来执行 manual tools

#### A.2.2 StdioTransport 子进程泄漏 (`mcp/transport.rs:100-107`)
```rust
pub struct StdioTransport {
    child: Option<Child>,
    // ...
}
// 缺少 Drop 实现！
```
**风险**:
1. 子进程异常退出时成为僵尸进程
2. 重复创建/销毁 MCP client 时进程累积
3. 资源泄漏（文件描述符、内存）

**修复**:
```rust
impl Drop for StdioTransport {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}
```

#### A.2.3 无上下文窗口管理
Agent loop 不检查消息长度，可能超出模型上下文限制（如 gpt-4 的 8k/32k/128k 限制）。

**Bifrost 的做法**:
- 使用 tokenizer 计算 token 数
- 超出时自动截断或报错
- 保留系统消息和最近的对话

### A.3 HTTP Client 配置不足

#### A.3.1 连接池配置单一 (`client/mod.rs:19`)
```rust
.pool_max_idle_per_host(100)
```
**缺少**:
- `connect_timeout`（默认无限制，可能永远挂起）
- `read_timeout` 与 `timeout` 区分
- `tcp_keepalive`（连接长期闲置后被防火墙断开）
- `pool_idle_timeout`（连接池清理策略）

**Bifrost 的配置**:
```go
// 连接超时、读取超时、写入超时分离
client := &fasthttp.Client{
    DialTimeout:     5 * time.Second,
    ReadTimeout:     30 * time.Second,
    WriteTimeout:    10 * time.Second,
    MaxConnsPerHost: 100,
}
```

#### A.3.2 无拦截器机制
无法统一添加：
- 请求签名（AWS Bedrock 需要）
- 请求/响应日志
- 指标收集
- 重试逻辑

### A.4 类型设计问题

#### A.4.1 `EmbeddingInput` 反序列化性能 (`types/embedding.rs:5-9`)
```rust
#[serde(untagged)]
pub enum EmbeddingInput {
    Single(String),
    Multiple(Vec<String>),
}
```
**问题**: `untagged` 导致 serde 尝试两种变体，性能差且错误信息不明确。

**建议**:
```rust
#[serde(untagged)]  // 或自定义 Visitor 提高性能
```
或显式使用新类型:
```rust
pub struct EmbeddingInput(serde_json::Value);
```

#### A.4.2 `ToolCallFunction::arguments` 类型错误 (`types/common.rs:50-53`)
```rust
pub struct ToolCallFunction {
    pub name: String,
    pub arguments: String,  // 应该是 serde_json::Value！
}
```
**问题**:
- 每次使用需要 `serde_json::from_str`，效率低
- 类型不安全，无法保证是有效 JSON
- Agent 中 (`mcp/agent.rs:117`) 使用 `unwrap_or` 处理解析失败，掩盖错误

### A.5 错误处理不一致

#### A.5.1 `is_retryable` 重复实现且分散
**位置 1** (`gateway/retry.rs:60-66`):
```rust
pub fn is_retryable(error: &GatewayError) -> bool {
    matches!(error.kind, ErrorKind::Network | ErrorKind::RateLimited | ErrorKind::MaxRetriesExceeded)
        || matches!(error.status_code, Some(500..=599))
        || matches!(error.status_code, Some(429))
}
```

**位置 2** (`error.rs:80-85`):
```rust
pub fn is_retryable(&self) -> bool {
    matches!(self.kind, ErrorKind::Network | ErrorKind::RateLimited | ErrorKind::MaxRetriesExceeded)
        || matches!(self.status_code, Some(500..=599 | 429))
}
```

**问题**:
- 两处逻辑相同但实现方式不同（`500..=599` vs `500..=599 | 429`）
- 修改时需要同步两处，容易遗漏
- `RetryPolicy` 应该只依赖 `GatewayError::is_retryable`

**建议**: 删除 `RetryPolicy::is_retryable`，统一使用 `GatewayError::is_retryable`。

#### A.5.2 无错误链 (`error.rs:21-30`)
```rust
pub struct GatewayError {
    pub kind: ErrorKind,
    pub message: String,
    // ... 无 source 字段
}
```
**问题**: 丢失了原始错误（如 reqwest::Error），调试时无法追溯根因。

**建议**:
```rust
pub struct GatewayError {
    // ...
    pub source: Option<Box<dyn std::error::Error + Send + Sync>>,
}
```

### A.6 中间件缺失

#### A.6.1 无认证中间件 (`http/middleware.rs`)
当前仅有 CORS 和 RequestId，缺少：
- **API Key 验证**: 从 Header 提取并验证
- **JWT/OAuth2**: 企业级认证
- **IP 白名单**: 访问控制

#### A.6.2 无指标中间件
无法收集：
- 请求延迟（P50/P95/P99）
- Provider 分布（哪个 Provider 被使用最多）
- 错误率（按 Provider、按错误类型）
- 队列等待时间

### A.7 代码组织问题

#### A.7.1 `src/lib.rs` 仅为测试存在
```rust
// src/lib.rs
pub mod client;
pub mod config;
// ...
```
**问题**: bin crate 通常不需要 lib，除非计划作为库发布。当前仅用于集成测试 (`tests/integration_tests.rs`)。

**建议**: 如果目标是独立服务，保留 lib 无意义。如果计划作为库，需要完善文档和公共 API。

#### A.7.2 `groq.rs` 过度包装
```rust
// src/provider/groq.rs
pub struct GroqProvider {
    inner: OpenAIProvider,
}
// 所有方法直接委托给 inner
```
**问题**: 增加维护成本（签名变更需改两处），却无实际功能差异。

**建议**: 使用类型别名或宏：
```rust
pub type GroqProvider = OpenAIProvider;
```

---

## 附录 B: 与 Bifrost 的深层能力对比

| 能力 | Heirloom | Bifrost | 影响 |
|------|----------|---------|------|
| **对象池** | ❌ 无 | ✅ sync.Pool | 高并发时 GC 压力大 |
| **Worker 模式** | ❌ 每请求 spawn | ✅ 预创建 workers | 调度开销显著 |
| **插件管道** | ❌ 无 | ✅ Pre/Post hooks | 无法扩展功能 |
| **错误链** | ❌ 丢失原始错误 | ✅ 保留完整链路 | 调试困难 |
| **连接池调优** | ❌ 仅 idle 数 | ✅ 完整配置 | 连接问题难排查 |
| **指标收集** | ❌ 无 | ✅ Prometheus | 无法监控 |
| **上下文管理** | ❌ 简单 struct | ✅ BifrostContext | 无法传递请求元数据 |
| **请求批处理** | ❌ 无 | ✅ Embedding 合并 | 成本高 |
| **语义缓存** | ❌ 无 | ✅ 向量相似度匹配 | 延迟和成本高 |
| **进程管理** | ❌ 僵尸进程风险 | ✅ 完善的 lifecycle | 资源泄漏 |

---

## 附录 C: 修复优先级矩阵

### 🔴 P0 - 生产阻塞（1周内）
1. **Streaming 绕过队列** (`gateway/mod.rs:170-188`)
2. **Provider 解析默认硬编码** (`gateway/mod.rs:237-245`)
3. **StdioTransport 子进程泄漏** (`mcp/transport.rs`)

### 🟡 P1 - 短期优化（2-4周）
4. **对象池实现**（减少 GC 压力）
5. **Worker 模式重构**（预创建处理器）
6. **错误链支持**（保留原始错误）
7. **依赖清理**（移除 `config` crate）
8. **统一 `is_retryable` 逻辑**
9. **HTTP Client 连接池配置完善**

### 🟢 P2 - 中期增强（1-3月）
10. **插件系统**（Pre/Post Hook 接口）
11. **指标收集**（Prometheus 格式）
12. **认证中间件**（API Key 验证）
13. **类型重构**（`ToolCallFunction.arguments` → Value）
14. **Agent 上下文窗口管理**
15. **MCP pending response 实现**

---

*审查完成。如需针对特定模块深入审查或制定实施计划，请告知。*