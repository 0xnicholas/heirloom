# Plan A: Heirloom Core Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a lightweight, production-ready Rust AI Gateway with OpenAI-compatible HTTP API, supporting multi-provider failover, retries, weighted key selection, and per-provider request queue isolation.

**Architecture:** Actix-web HTTP server → Gateway core (per-provider queues with lifecycle management, retry, fallback, key selection inside workers) → Provider clients (reqwest with connection pooling) → LLM APIs. No MCP, no plugins.

**Tech Stack:** actix-web, tokio, reqwest, serde, toml, config, tracing, thiserror, anyhow

---

## Scope

### Included
- OpenAI-compatible HTTP API (`/v1/chat/completions`, `/v1/embeddings`, `/v1/models`)
- 3 providers: OpenAI, Anthropic, Groq
- Chat Completion (streaming + non-streaming)
- Embedding
- List Models
- Automatic failover across fallback providers
- Retries with exponential backoff inside worker
- Weighted random API key selection inside worker
- Per-provider request queue with lifecycle management (shutdown, drain)
- SSE streaming with `[DONE]` signal
- Structured logging (tracing)
- Configuration validation at startup
- Cloud-native deployment (Docker, health checks)

### Excluded (Plan B)
- MCP Gateway
- Plugin system
- Governance (budget, rate limiting, virtual keys)
- Semantic caching
- WebSocket / Realtime API

---

## File Structure

```
heirloom/
├── Cargo.toml
├── Cargo.lock
├── Dockerfile
├── config.example.toml
├── src/
│   ├── main.rs                 # Entry: parse args, validate config, start server
│   ├── config.rs               # Configuration structs, loading, validation
│   ├── error.rs                # Error types with structured context
│   ├── context.rs              # Request context (request_id, retry_count, fallback_index)
│   ├── types/
│   │   ├── mod.rs              # Re-exports
│   │   ├── chat.rs             # Chat completion request/response types
│   │   ├── embedding.rs        # Embedding request/response types
│   │   ├── models.rs           # Model list types
│   │   └── common.rs           # Shared types (Usage, Tool, etc.)
│   ├── provider/
│   │   ├── mod.rs              # Provider trait + registry
│   │   ├── openai.rs           # OpenAI provider implementation
│   │   ├── anthropic.rs        # Anthropic provider (Messages API adapter)
│   │   └── groq.rs             # Groq provider (delegates to OpenAI)
│   ├── gateway/
│   │   ├── mod.rs              # Public Gateway API
│   │   ├── queue.rs            # ProviderQueue with lifecycle management
│   │   ├── retry.rs            # RetryPolicy with exponential backoff
│   │   ├── fallback.rs         # FallbackChain logic
│   │   └── key_selector.rs     # Weighted random key selection
│   ├── http/
│   │   ├── mod.rs              # Server initialization
│   │   ├── handlers.rs         # Actix route handlers
│   │   └── middleware.rs       # Logging, CORS, request ID
│   └── client/
│       └── mod.rs              # reqwest HTTP client wrapper
└── tests/
    ├── unit/
    │   ├── config_tests.rs
    │   ├── key_selector_tests.rs
    │   ├── retry_tests.rs
    │   └── fallback_tests.rs
    └── integration/
        ├── chat_tests.rs
        ├── embedding_tests.rs
        ├── stream_tests.rs
        └── fallback_tests.rs
```

---

## Design Decisions (Aligned with Bifrost)

### 1. Worker 职责范围（参考 Bifrost `requestWorker`）

**Bifrost 模式：** Worker 内部处理 key 选择 + 重试 → 避免并发竞争

**关键差异：** Bifrost 的 `keyProvider` 是一个闭包，每次请求时动态选择 key（支持重试时换 key）：
```go
keyProvider = func(usedKeyIDs map[string]bool) (schemas.Key, error) {
    available := make([]schemas.Key, 0, len(pool))
    for _, k := range pool {
        if !usedKeyIDs[k.ID] {
            available = append(available, k)
        }
    }
    if len(available) == 0 {
        // All keys exhausted — start a fresh weighted round
        for id := range usedKeyIDs {
            delete(usedKeyIDs, id)
        }
        available = pool
    }
    return bifrost.keySelector(req.Context, available, provKey, mdl)
}
```

**Heirloom 实现：**
```rust
// Provider 存储所有 keys，Worker 每次请求时选择
pub struct OpenAIProvider {
    base_url: String,
    keys: Vec<WeightedKey>,  // 所有 keys，不是单个
    // ...
}

// Worker 中每次请求时选择 key（支持重试轮换）
async fn worker_loop(
    provider: Arc<dyn Provider>,
    retry_policy: RetryPolicy,
    mut rx: mpsc::Receiver<QueuedRequest>,
    mut shutdown: broadcast::Receiver<()>,
) {
    loop {
        tokio::select! {
            Some(req) = rx.recv() => {
                let result = retry_policy.execute(|| async {
                    // Key 选择在 Provider 内部完成
                    provider.chat_completion(req.request.clone()).await
                }).await;
                let _ = req.response_tx.send(result);
            }
            _ = shutdown.recv() => {
                drain_queue(&mut rx).await;
                break;
            }
        }
    }
}
```

**关键设计：**
- Provider 存储 `Vec<WeightedKey>`（所有 keys）
- 每次请求时，Provider 内部调用 `select_key()` 选择
- 重试时自动换 key（排除已用过的 key，全部用完则重置）
- 避免多个 worker 竞争同一个 key

### 2. ProviderQueue 生命周期（参考 Bifrost `ProviderQueue`）

**Bifrost 结构：**
```go
type ProviderQueue struct {
    queue      chan *ChannelMessage  // request channel
    done       chan struct{}         // shutdown signal
    closing    uint32                // atomic: 0=open, 1=closing
    signalOnce sync.Once             // ensure signal once
    closeOnce  sync.Once             // ensure close once
}
```

**Heirloom 实现：**
```rust
pub struct ProviderQueue {
    tx: mpsc::Sender<QueuedRequest>,
    shutdown_tx: broadcast::Sender<()>,
    closing: AtomicBool,
}

impl ProviderQueue {
    pub async fn shutdown(&self) {
        if self.closing.swap(true, Ordering::SeqCst) {
            return; // Already closing
        }
        let _ = self.shutdown_tx.send(());
    }
    
    pub fn is_closing(&self) -> bool {
        self.closing.load(Ordering::SeqCst)
    }
}
```

### 3. 配置验证（参考 Bifrost `CheckAndSetDefaults`）

**Bifrost 模式：** `ProviderConfig.CheckAndSetDefaults()` 在初始化时验证并填充默认值

**Heirloom 实现：**
```rust
impl AppConfig {
    pub fn validate(&self) -> Result<(), ConfigError> {
        // Check at least one provider enabled
        // Check provider URLs are valid
        // Check API keys non-empty
        // Check fallback chains reference valid providers
        // Set defaults for missing fields
    }
}
```

### 4. 错误上下文（参考 Bifrost `BifrostError.ExtraFields`）

**Bifrost 结构：**
```go
type BifrostErrorExtraFields struct {
    RequestType            RequestType
    Provider               ModelProvider
    OriginalModelRequested string
    ResolvedModelUsed      string
    Latency                int64
    RetryCount             int
    FallbackIndex          int
}
```

**Heirloom 实现：**
```rust
pub struct GatewayError {
    pub kind: ErrorKind,
    pub message: String,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub status_code: Option<u16>,
    pub retry_count: Option<u32>,
    pub fallback_index: Option<u32>,
}

// Automatically populated by worker before returning
```

### 5. 请求上下文（参考 Bifrost `BifrostContext`）

**Bifrost：** 自定义 context.Context 支持 SetValue（线程安全）

**Heirloom：** 使用 Actix 的 `Extensions` + 自定义 `RequestContext`：
```rust
pub struct RequestContext {
    pub request_id: String,
    pub provider_name: String,
    pub model: String,
    pub retry_count: AtomicU32,
    pub fallback_index: AtomicU32,
}

// Stored in Actix request extensions, accessible in handlers and middleware
```

---

## Milestones

### Milestone 1: Project Skeleton & Configuration

**Goal:** Initialize Cargo project with dependencies, configuration loading, and validation.

#### Task 1.1: Initialize Cargo Project

**Files:**
- Create: `Cargo.toml`
- Create: `.gitignore`
- Create: `src/main.rs`

- [ ] **Step 1: Create Cargo.toml**

```toml
[package]
name = "heirloom"
version = "0.1.0"
edition = "2021"

[dependencies]
actix-web = "4.9"
actix-cors = "0.7"
tokio = { version = "1.40", features = ["full"] }
reqwest = { version = "0.12", features = ["json", "stream", "rustls-tls"] }
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
toml = "0.8"
config = "0.14"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter", "json"] }
thiserror = "1.0"
anyhow = "1.0"
futures = "0.3"
tokio-stream = "0.1"
bytes = "1.7"
uuid = { version = "1.11", features = ["v4"] }
chrono = "0.4"
rand = "0.8"
async-trait = "0.1"
url = "2.5"

[dev-dependencies]
tempfile = "3.14"
wiremock = "0.6"
```

- [ ] **Step 2: Create src/main.rs**

```rust
use tracing::{info, error};
use std::path::Path;

mod config;
mod context;
mod error;
mod types;
mod provider;
mod client;
mod gateway;
mod http;

use config::AppConfig;

#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        error!("Fatal error: {}", e);
        std::process::exit(1);
    }
}

async fn run() -> anyhow::Result<()> {
    let config_path = std::env::var("HEIRLOOM_CONFIG_PATH")
        .unwrap_or_else(|_| "config.toml".to_string());
    
    let config = AppConfig::from_file_with_env(Path::new(&config_path))?;
    config.validate()?;
    
    tracing_subscriber::fmt()
        .with_env_filter(&config.server.log_level)
        .init();
    
    info!("Loading configuration from {}", config_path);
    info!("Starting Heirloom on {}:{}", config.server.host, config.server.port);
    
    // TODO: Initialize Gateway and start server
    
    Ok(())
}
```

- [ ] **Step 3: Build project**

Run: `cargo build`
Expected: Compiles successfully

- [ ] **Step 4: Commit**

```bash
git add Cargo.toml Cargo.lock src/main.rs .gitignore
git commit -m "feat: initialize cargo project with dependencies"
```

#### Task 1.2: Configuration Types with Validation

**Files:**
- Create: `src/config.rs`
- Create: `tests/unit/config_tests.rs`

- [ ] **Step 1: Write failing test**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple_config() {
        let toml = r#"
[server]
host = "0.0.0.0"
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [
    { value = "sk-xxx", weight = 1.0 }
]
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert_eq!(config.server.port, 8080);
        assert!(config.providers.contains_key("openai"));
    }

    #[test]
    fn test_validate_rejects_empty_keys() {
        let toml = r#"
[server]
port = 8080

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = []
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.validate().is_err());
    }
}
```

Run: `cargo test config_tests`
Expected: FAIL — `AppConfig` not defined

- [ ] **Step 2: Implement config types with validation**

```rust
// src/config.rs
use serde::Deserialize;
use std::collections::HashMap;
use std::path::Path;
use thiserror::Error;
use url::Url;

#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub providers: HashMap<String, ProviderConfig>,
    #[serde(default)]
    pub fallbacks: Vec<FallbackChainConfig>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ServerConfig {
    #[serde(default = "default_host")]
    pub host: String,
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default = "default_log_level")]
    pub log_level: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ProviderConfig {
    pub enabled: bool,
    #[serde(default = "default_base_url")]
    pub base_url: String,
    pub keys: Vec<ApiKey>,
    #[serde(default = "default_max_retries")]
    pub max_retries: u32,
    #[serde(default = "default_retry_backoff_initial_ms")]
    pub retry_backoff_initial_ms: u64,
    #[serde(default = "default_retry_backoff_max_ms")]
    pub retry_backoff_max_ms: u64,
    #[serde(default = "default_request_timeout_seconds")]
    pub request_timeout_seconds: u64,
    #[serde(default = "default_queue_concurrency")]
    pub queue_concurrency: usize,
    #[serde(default = "default_queue_buffer_size")]
    pub queue_buffer_size: usize,
    #[serde(default)]
    pub network: NetworkConfig,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct NetworkConfig {
    #[serde(default)]
    pub extra_headers: HashMap<String, String>,
    #[serde(default)]
    pub insecure_skip_verify: bool,
    #[serde(default)]
    pub enforce_http2: bool,
    #[serde(default)]
    pub proxy_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ApiKey {
    pub value: String,
    #[serde(default = "default_weight")]
    pub weight: f64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct FallbackChainConfig {
    pub primary: String,
    pub fallbacks: Vec<String>,
}

#[derive(Error, Debug)]
pub enum ConfigError {
    #[error("No providers configured or all disabled")]
    NoProviders,
    #[error("Provider '{0}' has no API keys configured")]
    NoKeys(String),
    #[error("Provider '{0}' has invalid base URL: {1}")]
    InvalidUrl(String, String),
    #[error("Fallback chain references unknown provider: {0}")]
    UnknownFallbackProvider(String),
}

impl AppConfig {
    pub fn from_file(path: &Path) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path)?;
        let config: AppConfig = toml::from_str(&content)?;
        Ok(config)
    }
    
    pub fn from_file_with_env(path: &Path) -> anyhow::Result<Self> {
        let mut config = Self::from_file(path)?;
        
        if let Ok(port) = std::env::var("HEIRLOOM_PORT") {
            config.server.port = port.parse()?;
        }
        if let Ok(level) = std::env::var("HEIRLOOM_LOG_LEVEL") {
            config.server.log_level = level;
        }
        
        Ok(config)
    }
    
    pub fn validate(&self) -> Result<(), ConfigError> {
        let enabled_providers: Vec<&String> = self.providers.iter()
            .filter(|(_, p)| p.enabled)
            .map(|(n, _)| n)
            .collect();
        
        if enabled_providers.is_empty() {
            return Err(ConfigError::NoProviders);
        }
        
        for (name, provider) in &self.providers {
            if !provider.enabled {
                continue;
            }
            
            if provider.keys.is_empty() {
                return Err(ConfigError::NoKeys(name.clone()));
            }
            
            if !provider.base_url.is_empty() {
                Url::parse(&provider.base_url)
                    .map_err(|_| ConfigError::InvalidUrl(name.clone(), provider.base_url.clone()))?;
            }
        }
        
        for chain in &self.fallbacks {
            if !enabled_providers.contains(&&chain.primary) {
                return Err(ConfigError::UnknownFallbackProvider(chain.primary.clone()));
            }
            for fallback in &chain.fallbacks {
                if !enabled_providers.contains(&&fallback) {
                    return Err(ConfigError::UnknownFallbackProvider(fallback.clone()));
                }
            }
        }
        
        Ok(())
    }
}

fn default_host() -> String { "0.0.0.0".to_string() }
fn default_port() -> u16 { 8080 }
fn default_log_level() -> String { "info".to_string() }
fn default_base_url() -> String { "".to_string() }
fn default_max_retries() -> u32 { 3 }
fn default_retry_backoff_initial_ms() -> u64 { 500 }
fn default_retry_backoff_max_ms() -> u64 { 5000 }
fn default_request_timeout_seconds() -> u64 { 30 }
fn default_queue_concurrency() -> usize { 100 }
fn default_queue_buffer_size() -> usize { 1000 }
fn default_weight() -> f64 { 1.0 }
```

- [ ] **Step 3: Run tests**

Run: `cargo test config_tests`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/config.rs tests/unit/config_tests.rs Cargo.toml
git commit -m "feat: add configuration types with validation"
```

### Milestone 2: Core Types

**Goal:** Define all request/response types matching OpenAI API schema.

#### Task 2.1: Common and Chat Types

**Files:**
- Create: `src/types/mod.rs`
- Create: `src/types/common.rs`
- Create: `src/types/chat.rs`

- [ ] **Step 1: Implement common types**

```rust
// src/types/common.rs
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Usage {
    pub prompt_tokens: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub completion_tokens: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total_tokens: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiError {
    pub message: String,
    #[serde(rename = "type")]
    pub error_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorResponse {
    pub error: ApiError,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Tool {
    #[serde(rename = "type")]
    pub tool_type: String,
    pub function: FunctionTool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FunctionTool {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub parameters: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    #[serde(rename = "type")]
    pub call_type: String,
    pub function: ToolCallFunction,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCallFunction {
    pub name: String,
    pub arguments: String,
}
```

- [ ] **Step 2: Implement chat types**

```rust
// src/types/chat.rs
use serde::{Deserialize, Serialize};
use super::common::*;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatCompletionRequest {
    pub model: String,
    pub messages: Vec<ChatMessage>,
    #[serde(default)]
    pub stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub top_p: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub frequency_penalty: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub presence_penalty: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stop: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<Tool>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_choice: Option<ToolChoice>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "role", rename_all = "lowercase")]
pub enum ChatMessage {
    System {
        content: String,
    },
    User {
        content: String,
    },
    Assistant {
        #[serde(skip_serializing_if = "Option::is_none")]
        content: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        tool_calls: Option<Vec<ToolCall>>,
    },
    Tool {
        tool_call_id: String,
        content: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ToolChoice {
    Auto(String),
    None(String),
    Function { function: FunctionChoice },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FunctionChoice {
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatCompletionResponse {
    pub id: String,
    pub object: String,
    pub created: u64,
    pub model: String,
    pub choices: Vec<Choice>,
    pub usage: Usage,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Choice {
    pub index: i32,
    pub message: ResponseMessage,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub finish_reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResponseMessage {
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatCompletionChunk {
    pub id: String,
    pub object: String,
    pub created: u64,
    pub model: String,
    pub choices: Vec<ChunkChoice>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChunkChoice {
    pub index: i32,
    pub delta: DeltaMessage,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub finish_reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DeltaMessage {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub role: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
}
```

- [ ] **Step 3: Add mod.rs and test**

```rust
// src/types/mod.rs
pub mod chat;
pub mod common;

pub use chat::*;
pub use common::*;
```

Add to `src/main.rs`:
```rust
mod types;
```

Run: `cargo build`
Expected: Compiles successfully

- [ ] **Step 4: Commit**

```bash
git add src/types/
git commit -m "feat: add core request/response types"
```

#### Task 2.2: Embedding and Model Types

**Files:**
- Create: `src/types/embedding.rs`
- Create: `src/types/models.rs`

- [ ] **Step 1: Implement embedding types**

```rust
// src/types/embedding.rs
use serde::{Deserialize, Serialize};
use super::common::*;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum EmbeddingInput {
    Single(String),
    Multiple(Vec<String>),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingRequest {
    pub model: String,
    pub input: EmbeddingInput,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingResponse {
    pub object: String,
    pub data: Vec<EmbeddingData>,
    pub model: String,
    pub usage: Usage,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingData {
    pub object: String,
    pub embedding: Vec<f64>,
    pub index: i32,
}
```

- [ ] **Step 2: Implement model types**

```rust
// src/types/models.rs
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelList {
    pub object: String,
    pub data: Vec<Model>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Model {
    pub id: String,
    pub object: String,
    pub created: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub owned_by: Option<String>,
}
```

- [ ] **Step 3: Update mod.rs**

```rust
// src/types/mod.rs
pub mod chat;
pub mod common;
pub mod embedding;
pub mod models;

pub use chat::*;
pub use common::*;
pub use embedding::*;
pub use models::*;
```

- [ ] **Step 4: Commit**

```bash
git add src/types/
git commit -m "feat: add embedding and model list types"
```

### Milestone 3: Error Types and Request Context

**Goal:** Structured error handling with context, and request context for tracing.

#### Task 3.1: Error Types with Context

**Files:**
- Create: `src/error.rs`

- [ ] **Step 1: Implement error types**

```rust
// src/error.rs
use thiserror::Error;

#[derive(Error, Debug, Clone)]
pub enum ErrorKind {
    #[error("provider error")]
    Provider,
    #[error("network error")]
    Network,
    #[error("rate limited")]
    RateLimited,
    #[error("invalid request")]
    InvalidRequest,
    #[error("authentication failed")]
    AuthenticationFailed,
    #[error("no provider available")]
    NoProviderAvailable,
    #[error("max retries exceeded")]
    MaxRetriesExceeded,
}

#[derive(Debug, Clone)]
pub struct GatewayError {
    pub kind: ErrorKind,
    pub message: String,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub status_code: Option<u16>,
    pub retry_count: Option<u32>,
    pub fallback_index: Option<u32>,
}

impl GatewayError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            provider: None,
            model: None,
            status_code: None,
            retry_count: None,
            fallback_index: None,
        }
    }
    
    pub fn with_provider(mut self, provider: impl Into<String>) -> Self {
        self.provider = Some(provider.into());
        self
    }
    
    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.model = Some(model.into());
        self
    }
    
    pub fn with_status_code(mut self, code: u16) -> Self {
        self.status_code = Some(code);
        self
    }
    
    pub fn with_retry_count(mut self, count: u32) -> Self {
        self.retry_count = Some(count);
        self
    }
    
    pub fn with_fallback_index(mut self, index: u32) -> Self {
        self.fallback_index = Some(index);
        self
    }
    
    pub fn status_code(&self) -> u16 {
        match self.kind {
            ErrorKind::InvalidRequest => 400,
            ErrorKind::AuthenticationFailed => 401,
            ErrorKind::RateLimited => 429,
            ErrorKind::NoProviderAvailable => 503,
            _ => self.status_code.unwrap_or(500),
        }
    }
    
    pub fn is_retryable(&self) -> bool {
        matches!(self.kind,
            ErrorKind::Network |
            ErrorKind::RateLimited |
            ErrorKind::MaxRetriesExceeded
        ) || matches!(self.status_code, Some(500..=599 | 429))
    }
}

impl std::fmt::Display for GatewayError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)?;
        if let Some(provider) = &self.provider {
            write!(f, " [provider={}]", provider)?;
        }
        if let Some(model) = &self.model {
            write!(f, " [model={}]", model)?;
        }
        if let Some(retry) = self.retry_count {
            write!(f, " [retry={}]", retry)?;
        }
        if let Some(fallback) = self.fallback_index {
            write!(f, " [fallback={}]", fallback)?;
        }
        Ok(())
    }
}
```

- [ ] **Step 2: Test error building**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_builder() {
        let err = GatewayError::new(ErrorKind::Provider, "test error")
            .with_provider("openai")
            .with_model("gpt-4")
            .with_status_code(500)
            .with_retry_count(2);
        
        assert_eq!(err.status_code(), 500);
        assert!(err.is_retryable());
        assert!(err.to_string().contains("[provider=openai]"));
    }
}
```

Run: `cargo test error::tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/error.rs
git commit -m "feat: add structured error types with context"
```

#### Task 3.2: Request Context

**Files:**
- Create: `src/context.rs`

- [ ] **Step 1: Implement request context**

```rust
// src/context.rs
use std::sync::atomic::{AtomicU32, Ordering};
use uuid::Uuid;

#[derive(Debug)]
pub struct RequestContext {
    pub request_id: String,
    pub provider_name: String,
    pub model: String,
    pub retry_count: AtomicU32,
    pub fallback_index: AtomicU32,
}

impl RequestContext {
    pub fn new(provider_name: impl Into<String>, model: impl Into<String>) -> Self {
        Self {
            request_id: Uuid::new_v4().to_string(),
            provider_name: provider_name.into(),
            model: model.into(),
            retry_count: AtomicU32::new(0),
            fallback_index: AtomicU32::new(0),
        }
    }
    
    pub fn increment_retry(&self) -> u32 {
        self.retry_count.fetch_add(1, Ordering::SeqCst)
    }
    
    pub fn get_retry_count(&self) -> u32 {
        self.retry_count.load(Ordering::SeqCst)
    }
    
    pub fn set_fallback_index(&self, index: u32) {
        self.fallback_index.store(index, Ordering::SeqCst);
    }
    
    pub fn get_fallback_index(&self) -> u32 {
        self.fallback_index.load(Ordering::SeqCst)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/context.rs
git commit -m "feat: add request context with retry and fallback tracking"
```

### Milestone 4: Provider Trait and Implementations

**Goal:** Define Provider trait and implement OpenAI, Anthropic, Groq providers.

#### Task 4.1: Provider Trait

**Files:**
- Create: `src/provider/mod.rs`

- [ ] **Step 1: Define Provider trait**

```rust
// src/provider/mod.rs
use async_trait::async_trait;
use futures::stream::BoxStream;
use std::sync::Arc;

use crate::error::GatewayError;
use crate::types::*;

#[async_trait]
pub trait Provider: Send + Sync {
    fn name(&self) -> &'static str;
    
    async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError>;
    
    async fn chat_completion_stream(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError>;
    
    async fn embedding(
        &self,
        request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError>;
    
    async fn list_models(&self) -> Result<ModelList, GatewayError>;
}

pub type ProviderRef = Arc<dyn Provider>;
```

- [ ] **Step 2: Commit**

```bash
git add src/provider/mod.rs
git commit -m "feat: define Provider trait"
```

#### Task 4.2: HTTP Client Wrapper

**Files:**
- Create: `src/client/mod.rs`

- [ ] **Step 1: Implement HTTP client**

```rust
// src/client/mod.rs
use reqwest::Client;
use std::time::Duration;

pub struct HttpClient {
    client: Client,
}

impl HttpClient {
    pub fn new(timeout_seconds: u64) -> Self {
        let client = Client::builder()
            .timeout(Duration::from_secs(timeout_seconds))
            .pool_max_idle_per_host(100)
            .build()
            .expect("Failed to build HTTP client");
        
        Self { client }
    }
    
    pub fn inner(&self) -> &Client {
        &self.client
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/client/mod.rs
git commit -m "feat: add HTTP client wrapper"
```

#### Task 4.3: OpenAI Provider

**Files:**
- Create: `src/provider/openai.rs`

- [ ] **Step 1: Implement OpenAI provider**

```rust
// src/provider/openai.rs
use async_trait::async_trait;
use futures::stream::{self, BoxStream, StreamExt};
use reqwest::header::{AUTHORIZATION, CONTENT_TYPE};

use crate::client::HttpClient;
use crate::error::{ErrorKind, GatewayError};
use crate::provider::Provider;
use crate::types::*;

pub struct OpenAIProvider {
    base_url: String,
    keys: Vec<WeightedKey>,  // All keys, selected dynamically per-request
    timeout_seconds: u64,
    client: HttpClient,
}

impl OpenAIProvider {
    pub fn new(base_url: String, keys: Vec<WeightedKey>, timeout_seconds: u64) -> Self {
        // Set default base_url if empty (like Bifrost)
        let base_url = if base_url.is_empty() {
            "https://api.openai.com".to_string()
        } else {
            base_url.trim_end_matches('/').to_string()
        };
        
        Self {
            base_url,
            keys,
            timeout_seconds,
            client: HttpClient::new(timeout_seconds),
        }
    }
    
    fn select_key(&self) -> &WeightedKey {
        select_key(&self.keys)
    }
    
    fn auth_header(&self) -> String {
        format!("Bearer {}", self.select_key().value)
    }
    
    fn handle_error(status: reqwest::StatusCode, body: &str) -> GatewayError {
        if let Ok(err_resp) = serde_json::from_str::<ErrorResponse>(body) {
            let mut err = GatewayError::new(ErrorKind::Provider, err_resp.error.message)
                .with_status_code(status.as_u16());
            if let Some(code) = err_resp.error.code {
                err = err.with_status_code(code.parse().unwrap_or(status.as_u16()));
            }
            err
        } else {
            GatewayError::new(ErrorKind::Provider, format!("HTTP {}: {}", status, body))
                .with_status_code(status.as_u16())
        }
    }
}

#[async_trait]
impl Provider for OpenAIProvider {
    fn name(&self) -> &'static str {
        "openai"
    }
    
    async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        let url = format!("{}/v1/chat/completions", self.base_url);
        
        let response = self.client.inner()
            .post(&url)
            .header(AUTHORIZATION, self.auth_header())
            .header(CONTENT_TYPE, "application/json")
            .json(&request)
            .send()
            .await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        if status.is_success() {
            serde_json::from_str(&body)
                .map_err(|e| GatewayError::new(ErrorKind::Provider, format!("Parse error: {}", e)))
        } else {
            Err(Self::handle_error(status, &body))
        }
    }
    
    async fn chat_completion_stream(
        &self,
        mut request: ChatCompletionRequest,
    ) -> Result<BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
        let url = format!("{}/v1/chat/completions", self.base_url);
        request.stream = true;
        
        let response = self.client.inner()
            .post(&url)
            .header(AUTHORIZATION, self.auth_header())
            .header(CONTENT_TYPE, "application/json")
            .header("Accept", "text/event-stream")
            .json(&request)
            .send()
            .await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        let status = response.status();
        if !status.is_success() {
            let body = response.text().await
                .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
            return Err(Self::handle_error(status, &body));
        }
        
        let stream = response.bytes_stream()
            .map(|chunk| {
                match chunk {
                    Ok(bytes) => {
                        let text = String::from_utf8_lossy(&bytes);
                        parse_sse_chunk(&text)
                    }
                    Err(e) => Err(GatewayError::new(ErrorKind::Network, e.to_string())),
                }
            })
            .filter_map(|result| async move {
                match result {
                    Ok(Some(chunk)) => Some(Ok(chunk)),
                    Ok(None) => None,
                    Err(e) => Some(Err(e)),
                }
            })
            .chain(stream::once(async {
                Ok(ChatCompletionChunk {
                    id: String::new(),
                    object: "chat.completion.chunk".to_string(),
                    created: 0,
                    model: String::new(),
                    choices: vec![],
                })
            }));
        
        Ok(Box::pin(stream))
    }
    
    async fn embedding(
        &self,
        request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError> {
        let url = format!("{}/v1/embeddings", self.base_url);
        
        let response = self.client.inner()
            .post(&url)
            .header(AUTHORIZATION, self.auth_header())
            .header(CONTENT_TYPE, "application/json")
            .json(&request)
            .send()
            .await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        if status.is_success() {
            serde_json::from_str(&body)
                .map_err(|e| GatewayError::new(ErrorKind::Provider, format!("Parse error: {}", e)))
        } else {
            Err(Self::handle_error(status, &body))
        }
    }
    
    async fn list_models(&self) -> Result<ModelList, GatewayError> {
        let url = format!("{}/v1/models", self.base_url);
        
        let response = self.client.inner()
            .get(&url)
            .header(AUTHORIZATION, self.auth_header())
            .send()
            .await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        if status.is_success() {
            serde_json::from_str(&body)
                .map_err(|e| GatewayError::new(ErrorKind::Provider, format!("Parse error: {}", e)))
        } else {
            Err(Self::handle_error(status, &body))
        }
    }
}

fn parse_sse_chunk(text: &str) -> Result<Option<ChatCompletionChunk>, GatewayError> {
    for line in text.lines() {
        let line = line.trim();
        if line.starts_with("data: ") {
            let data = &line[6..];
            if data == "[DONE]" {
                return Ok(None);
            }
            let chunk: ChatCompletionChunk = serde_json::from_str(data)
                .map_err(|e| GatewayError::new(ErrorKind::Provider, format!("SSE parse error: {}", e)))?;
            return Ok(Some(chunk));
        }
    }
    Ok(None)
}
```

- [ ] **Step 2: Test with mock server**

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{MockServer, Mock, ResponseTemplate};
    use wiremock::matchers::{method, path, header};

    #[tokio::test]
    async fn test_chat_completion() {
        let mock_server = MockServer::start().await;
        
        Mock::given(method("POST"))
            .and(path("/v1/chat/completions"))
            .and(header("authorization", "Bearer test-key"))
            .respond_with(ResponseTemplate::new(200)
                .set_body_json(serde_json::json!({
                    "id": "chat-123",
                    "object": "chat.completion",
                    "created": 1700000000,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello!"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15
                    }
                })))
            .mount(&mock_server)
            .await;
        
        let provider = OpenAIProvider::new(
            mock_server.uri(),
            "test-key".to_string(),
            30,
        );
        
        let request = ChatCompletionRequest {
            model: "gpt-4".to_string(),
            messages: vec![ChatMessage::User { content: "Hello".to_string() }],
            stream: false,
            temperature: None,
            max_tokens: None,
            top_p: None,
            frequency_penalty: None,
            presence_penalty: None,
            stop: None,
            tools: None,
            tool_choice: None,
            user: None,
        };
        
        let response = provider.chat_completion(request).await.unwrap();
        assert_eq!(response.id, "chat-123");
        assert_eq!(response.choices[0].message.content, Some("Hello!".to_string()));
    }
}
```

Run: `cargo test provider::openai::tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/provider/openai.rs Cargo.toml
git commit -m "feat: implement OpenAI provider with streaming support"
```

#### Task 4.4: Anthropic Provider

**Files:**
- Create: `src/provider/anthropic.rs`

- [ ] **Step 1: Implement Anthropic provider**

```rust
// src/provider/anthropic.rs
use async_trait::async_trait;
use futures::stream::BoxStream;
use reqwest::header::{HeaderMap, HeaderValue, CONTENT_TYPE};

use crate::client::HttpClient;
use crate::error::{ErrorKind, GatewayError};
use crate::provider::Provider;
use crate::types::*;

pub struct AnthropicProvider {
    base_url: String,
    keys: Vec<WeightedKey>,
    timeout_seconds: u64,
    client: HttpClient,
}

impl AnthropicProvider {
    pub fn new(base_url: String, keys: Vec<WeightedKey>, timeout_seconds: u64) -> Self {
        let base_url = if base_url.is_empty() {
            "https://api.anthropic.com".to_string()
        } else {
            base_url.trim_end_matches('/').to_string()
        };
        
        Self {
            base_url,
            keys,
            timeout_seconds,
            client: HttpClient::new(timeout_seconds),
        }
    }
    
    fn select_key(&self) -> &WeightedKey {
        select_key(&self.keys)
    }
    
    fn build_headers(&self) -> HeaderMap {
        let mut headers = HeaderMap::new();
        headers.insert("x-api-key", HeaderValue::from_str(&self.select_key().value).unwrap());
        headers.insert("anthropic-version", HeaderValue::from_static("2023-06-01"));
        headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
        headers
    }
    
    fn convert_request(request: &ChatCompletionRequest) -> AnthropicRequest {
        let mut messages = Vec::new();
        let mut system_msg = None;
        
        for msg in &request.messages {
            match msg {
                ChatMessage::System { content } => {
                    system_msg = Some(content.clone());
                }
                ChatMessage::User { content } => {
                    messages.push(AnthropicMessage {
                        role: "user".to_string(),
                        content: content.clone(),
                    });
                }
                ChatMessage::Assistant { content, .. } => {
                    messages.push(AnthropicMessage {
                        role: "assistant".to_string(),
                        content: content.clone().unwrap_or_default(),
                    });
                }
                ChatMessage::Tool { tool_call_id, content } => {
                    messages.push(AnthropicMessage {
                        role: "user".to_string(),
                        content: format!("Tool {} result: {}", tool_call_id, content),
                    });
                }
            }
        }
        
        AnthropicRequest {
            model: request.model.clone(),
            messages,
            max_tokens: request.max_tokens,
            temperature: request.temperature,
            top_p: request.top_p,
            system: system_msg,
            stream: Some(request.stream),
        }
    }
    
    fn convert_response(response: AnthropicResponse) -> ChatCompletionResponse {
        let content = response.content.iter()
            .filter_map(|c| match c {
                AnthropicContent::Text { text } => Some(text.clone()),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join("");
        
        ChatCompletionResponse {
            id: response.id,
            object: "chat.completion".to_string(),
            created: chrono::Utc::now().timestamp() as u64,
            model: response.model,
            choices: vec![Choice {
                index: 0,
                message: ResponseMessage {
                    role: "assistant".to_string(),
                    content: Some(content),
                    tool_calls: None,
                },
                finish_reason: Some("stop".to_string()),
            }],
            usage: Usage {
                prompt_tokens: response.usage.input_tokens,
                completion_tokens: Some(response.usage.output_tokens),
                total_tokens: Some(response.usage.input_tokens + response.usage.output_tokens),
            },
        }
    }
}

#[async_trait]
impl Provider for AnthropicProvider {
    fn name(&self) -> &'static str {
        "anthropic"
    }
    
    async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        let url = format!("{}/v1/messages", self.base_url);
        let anthropic_req = Self::convert_request(&request);
        
        let response = self.client.inner()
            .post(&url)
            .headers(self.build_headers())
            .json(&anthropic_req)
            .send()
            .await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| GatewayError::new(ErrorKind::Network, e.to_string()))?;
        
        if status.is_success() {
            let anthropic_resp: AnthropicResponse = serde_json::from_str(&body)
                .map_err(|e| GatewayError::new(ErrorKind::Provider, format!("Parse error: {}", e)))?;
            Ok(Self::convert_response(anthropic_resp))
        } else {
            Err(GatewayError::new(ErrorKind::Provider, format!("HTTP {}: {}", status, body))
                .with_status_code(status.as_u16()))
        }
    }
    
    async fn chat_completion_stream(
        &self,
        _request: ChatCompletionRequest,
    ) -> Result<BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
        Err(GatewayError::new(ErrorKind::Provider, "Streaming not yet implemented for Anthropic"))
    }
    
    async fn embedding(
        &self,
        _request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError> {
        Err(GatewayError::new(ErrorKind::Provider, "Embeddings not yet implemented for Anthropic"))
    }
    
    async fn list_models(&self) -> Result<ModelList, GatewayError> {
        Err(GatewayError::new(ErrorKind::Provider, "List models not yet implemented for Anthropic"))
    }
}

// Anthropic-specific types
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct AnthropicRequest {
    pub model: String,
    pub messages: Vec<AnthropicMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub top_p: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub system: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream: Option<bool>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct AnthropicMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct AnthropicResponse {
    pub id: String,
    #[serde(rename = "type")]
    pub response_type: String,
    pub role: String,
    pub content: Vec<AnthropicContent>,
    pub model: String,
    pub usage: AnthropicUsage,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type")]
enum AnthropicContent {
    #[serde(rename = "text")]
    Text { text: String },
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct AnthropicUsage {
    pub input_tokens: i64,
    pub output_tokens: i64,
}
```

- [ ] **Step 2: Commit**

```bash
git add src/provider/anthropic.rs
git commit -m "feat: implement Anthropic provider with format conversion"
```

#### Task 4.5: Groq Provider

**Files:**
- Create: `src/provider/groq.rs`

- [ ] **Step 1: Implement Groq provider**

```rust
// src/provider/groq.rs
use async_trait::async_trait;
use futures::stream::BoxStream;

use crate::error::GatewayError;
use crate::provider::{Provider, openai::OpenAIProvider};
use crate::types::*;

pub struct GroqProvider {
    inner: OpenAIProvider,
}

impl GroqProvider {
    pub fn new(base_url: String, keys: Vec<WeightedKey>, timeout_seconds: u64) -> Self {
        Self {
            inner: OpenAIProvider::new(base_url, keys, timeout_seconds),
        }
    }
}

#[async_trait]
impl Provider for GroqProvider {
    fn name(&self) -> &'static str {
        "groq"
    }
    
    async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        self.inner.chat_completion(request).await
    }
    
    async fn chat_completion_stream(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
        self.inner.chat_completion_stream(request).await
    }
    
    async fn embedding(
        &self,
        request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError> {
        self.inner.embedding(request).await
    }
    
    async fn list_models(&self) -> Result<ModelList, GatewayError> {
        self.inner.list_models().await
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/provider/groq.rs
git commit -m "feat: implement Groq provider (delegates to OpenAI)"
```

### Milestone 5: Gateway Core

**Goal:** Implement queues, retry, fallback, and key selection with worker-level execution.

#### Task 5.1: Key Selector

**Files:**
- Create: `src/gateway/key_selector.rs`
- Create: `tests/unit/key_selector_tests.rs`

- [ ] **Step 1: Implement weighted random selection (integer-based, like Bifrost)**

```rust
// src/gateway/key_selector.rs
use rand::Rng;

#[derive(Debug, Clone)]
pub struct WeightedKey {
    pub value: String,
    pub weight: f64,
}

pub fn select_key(keys: &[WeightedKey]) -> &WeightedKey {
    if keys.is_empty() {
        panic!("Cannot select key from empty list");
    }
    
    // Integer-based calculation (like Bifrost: weight * 100)
    let total_weight: u64 = keys.iter()
        .map(|k| (k.weight * 100.0) as u64)
        .sum();
    
    if total_weight == 0 {
        // Fall back to uniform random
        let idx = rand::thread_rng().gen_range(0..keys.len());
        return &keys[idx];
    }
    
    let mut rng = rand::thread_rng();
    let mut point = rng.gen_range(0..total_weight);
    
    for key in keys {
        let weight = (key.weight * 100.0) as u64;
        if point < weight {
            return key;
        }
        point -= weight;
    }
    
    &keys[keys.len() - 1]
}
```

- [ ] **Step 2: Run tests**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_weighted_distribution() {
        let keys = vec![
            WeightedKey { value: "key-a".to_string(), weight: 1.0 },
            WeightedKey { value: "key-b".to_string(), weight: 3.0 },
        ];
        
        let mut counts = std::collections::HashMap::new();
        for _ in 0..1000 {
            let selected = select_key(&keys);
            *counts.entry(selected.value.clone()).or_insert(0) += 1;
        }
        
        let count_a = *counts.get("key-a").unwrap_or(&0) as f64;
        let count_b = *counts.get("key-b").unwrap_or(&0) as f64;
        
        let ratio = count_b / count_a;
        assert!(ratio > 2.0 && ratio < 4.0, "Ratio was {}", ratio);
    }
}
```

Run: `cargo test key_selector_tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/gateway/key_selector.rs tests/unit/key_selector_tests.rs
git commit -m "feat: implement weighted random key selection (integer-based)"
```

#### Task 5.2: Retry Policy

**Files:**
- Create: `src/gateway/retry.rs`
- Create: `tests/unit/retry_tests.rs`

- [ ] **Step 1: Implement retry policy**

```rust
// src/gateway/retry.rs
use std::time::Duration;
use tokio::time::sleep;
use rand::Rng;

use crate::error::{ErrorKind, GatewayError};

pub struct RetryPolicy {
    max_retries: u32,
    backoff_initial: Duration,
    backoff_max: Duration,
}

impl RetryPolicy {
    pub fn new(max_retries: u32, backoff_initial_ms: u64, backoff_max_ms: u64) -> Self {
        Self {
            max_retries,
            backoff_initial: Duration::from_millis(backoff_initial_ms),
            backoff_max: Duration::from_millis(backoff_max_ms),
        }
    }
    
    pub async fn execute<F, Fut, T>(
        &self,
        mut operation: F,
    ) -> Result<T, GatewayError>
    where
        F: FnMut() -> Fut,
        Fut: std::future::Future<Output = Result<T, GatewayError>>,
    {
        let mut last_error = None;
        
        for attempt in 0..=self.max_retries {
            match operation().await {
                Ok(result) => return Ok(result),
                Err(e) => {
                    if !e.is_retryable() || attempt == self.max_retries {
                        return Err(e);
                    }
                    last_error = Some(e);
                    let backoff = self.calculate_backoff(attempt);
                    sleep(backoff).await;
                }
            }
        }
        
        Err(last_error.unwrap_or_else(|| 
            GatewayError::new(ErrorKind::MaxRetriesExceeded, "Max retries exceeded")
        ))
    }
    
    fn calculate_backoff(&self, attempt: u32) -> Duration {
        let base = self.backoff_initial.as_millis() as u64 * 2u64.pow(attempt);
        let capped = std::cmp::min(base, self.backoff_max.as_millis() as u64);
        let jitter = rand::thread_rng().gen_range(0..capped / 10);
        Duration::from_millis(capped + jitter)
    }
}
```

- [ ] **Step 2: Run tests**

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[tokio::test]
    async fn test_retry_succeeds_eventually() {
        let policy = RetryPolicy::new(3, 10, 100);
        let attempts = std::sync::Arc::new(AtomicUsize::new(0));
        let attempts_clone = attempts.clone();
        
        let result = policy.execute(|| async {
            let attempt = attempts_clone.fetch_add(1, Ordering::SeqCst);
            if attempt < 2 {
                Err(GatewayError::new(ErrorKind::Network, "fail"))
            } else {
                Ok("success")
            }
        }).await;
        
        assert_eq!(result.unwrap(), "success");
        assert_eq!(attempts.load(Ordering::SeqCst), 3);
    }

    #[tokio::test]
    async fn test_no_retry_on_4xx() {
        let policy = RetryPolicy::new(3, 10, 100);
        let attempts = std::sync::Arc::new(AtomicUsize::new(0));
        let attempts_clone = attempts.clone();
        
        let result = policy.execute(|| async {
            attempts_clone.fetch_add(1, Ordering::SeqCst);
            Err(GatewayError::new(ErrorKind::InvalidRequest, "bad request"))
        }).await;
        
        assert!(result.is_err());
        assert_eq!(attempts.load(Ordering::SeqCst), 1); // No retries
    }
}
```

Run: `cargo test retry_tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/gateway/retry.rs tests/unit/retry_tests.rs
git commit -m "feat: implement retry policy with exponential backoff"
```

#### Task 5.3: Provider Queue with Lifecycle

**Files:**
- Create: `src/gateway/queue.rs`

- [ ] **Step 1: Implement ProviderQueue with shutdown and drain**

```rust
// src/gateway/queue.rs
use tokio::sync::{broadcast, mpsc, oneshot};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use crate::error::{ErrorKind, GatewayError};
use crate::types::*;
use crate::provider::ProviderRef;
use crate::gateway::key_selector::{select_key, WeightedKey};
use crate::gateway::retry::RetryPolicy;

pub struct QueuedRequest {
    pub request: GatewayRequest,
    pub response_tx: oneshot::Sender<GatewayResponse>,
}

pub enum GatewayRequest {
    ChatCompletion(ChatCompletionRequest),
    Embedding(EmbeddingRequest),
    ListModels,
}

pub enum GatewayResponse {
    ChatCompletion(Result<ChatCompletionResponse, GatewayError>),
    Embedding(Result<EmbeddingResponse, GatewayError>),
    ListModels(Result<ModelList, GatewayError>),
}

pub struct ProviderQueue {
    tx: mpsc::Sender<QueuedRequest>,
    shutdown_tx: broadcast::Sender<()>,
    closing: AtomicBool,
}

impl ProviderQueue {
    pub fn new(
        provider: ProviderRef,
        keys: Vec<WeightedKey>,
        retry_policy: RetryPolicy,
        concurrency: usize,
        buffer_size: usize,
    ) -> Self {
        let (tx, rx) = mpsc::channel::<QueuedRequest>(buffer_size);
        let (shutdown_tx, _) = broadcast::channel(1);
        
        for _ in 0..concurrency {
            let provider = provider.clone();
            let keys = keys.clone();
            let retry_policy = retry_policy.clone();
            let rx = rx.resubscribe();
            let mut shutdown = shutdown_tx.subscribe();
            
            tokio::spawn(async move {
                worker_loop(provider, keys, retry_policy, rx, shutdown).await;
            });
        }
        
        Self {
            tx,
            shutdown_tx,
            closing: AtomicBool::new(false),
        }
    }
    
    pub async fn send(
        &self,
        request: GatewayRequest,
    ) -> Result<GatewayResponse, GatewayError> {
        if self.is_closing() {
            return Err(GatewayError::new(ErrorKind::Provider, "Provider is shutting down"));
        }
        
        let (response_tx, response_rx) = oneshot::channel();
        
        self.tx.send(QueuedRequest { request, response_tx })
            .await
            .map_err(|_| GatewayError::new(ErrorKind::Provider, "Queue is full or closed"))?;
        
        response_rx.await
            .map_err(|_| GatewayError::new(ErrorKind::Provider, "Response channel closed"))
    }
    
    pub async fn shutdown(&self) {
        if self.closing.swap(true, Ordering::SeqCst) {
            return; // Already closing
        }
        let _ = self.shutdown_tx.send(());
    }
    
    pub fn is_closing(&self) -> bool {
        self.closing.load(Ordering::SeqCst)
    }
}

async fn worker_loop(
    provider: ProviderRef,
    keys: Vec<WeightedKey>,
    retry_policy: RetryPolicy,
    mut rx: mpsc::Receiver<QueuedRequest>,
    mut shutdown: broadcast::Receiver<()>,
) {
    loop {
        tokio::select! {
            Some(req) = rx.recv() => {
                let response = process_request(&provider, &keys, &retry_policy, req.request).await;
                let _ = req.response_tx.send(response);
            }
            _ = shutdown.recv() => {
                // Drain remaining requests
                while let Ok(req) = rx.try_recv() {
                    let err = GatewayResponse::ChatCompletion(
                        Err(GatewayError::new(ErrorKind::Provider, "Provider is shutting down"))
                    );
                    let _ = req.response_tx.send(err);
                }
                break;
            }
        }
    }
}

async fn process_request(
    provider: &ProviderRef,
    keys: &[WeightedKey],
    retry_policy: &RetryPolicy,
    request: GatewayRequest,
) -> GatewayResponse {
    let key = select_key(keys).value.clone();
    
    match request {
        GatewayRequest::ChatCompletion(req) => {
            let result = retry_policy.execute(|| async {
                provider.chat_completion(req.clone()).await
            }).await;
            GatewayResponse::ChatCompletion(result)
        }
        GatewayRequest::Embedding(req) => {
            let result = retry_policy.execute(|| async {
                provider.embedding(req.clone()).await
            }).await;
            GatewayResponse::Embedding(result)
        }
        GatewayRequest::ListModels => {
            let result = retry_policy.execute(|| async {
                provider.list_models().await
            }).await;
            GatewayResponse::ListModels(result)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/gateway/queue.rs
git commit -m "feat: implement per-provider queue with lifecycle management"
```

#### Task 5.4: Fallback Chain

**Files:**
- Create: `src/gateway/fallback.rs`
- Create: `tests/unit/fallback_tests.rs`

- [ ] **Step 1: Implement fallback chain**

```rust
// src/gateway/fallback.rs
use crate::error::{ErrorKind, GatewayError};
use crate::provider::ProviderRef;

pub struct FallbackChain {
    primary: ProviderRef,
    fallbacks: Vec<ProviderRef>,
}

impl FallbackChain {
    pub fn new(primary: ProviderRef, fallbacks: Vec<ProviderRef>) -> Self {
        Self { primary, fallbacks }
    }
    
    pub async fn execute<F, Fut, T>(
        &self,
        operation: F,
    ) -> Result<T, GatewayError>
    where
        F: Fn(&ProviderRef) -> Fut,
        Fut: std::future::Future<Output = Result<T, GatewayError>>,
    {
        // Try primary
        match operation(&self.primary).await {
            Ok(result) => return Ok(result),
            Err(e) => {
                if !e.is_retryable() {
                    return Err(e);
                }
            }
        }
        
        // Try fallbacks
        for (idx, fallback) in self.fallbacks.iter().enumerate() {
            match operation(fallback).await {
                Ok(result) => return Ok(result),
                Err(e) => {
                    if !e.is_retryable() {
                        return Err(e.with_fallback_index(idx as u32 + 1));
                    }
                }
            }
        }
        
        Err(GatewayError::new(ErrorKind::NoProviderAvailable, "All providers failed"))
    }
}
```

- [ ] **Step 2: Run tests**

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use crate::types::*;

    struct MockProvider {
        name: &'static str,
        should_fail: bool,
    }

    #[async_trait]
    impl Provider for MockProvider {
        fn name(&self) -> &'static str { self.name }
        
        async fn chat_completion(&self, _req: ChatCompletionRequest) -> Result<ChatCompletionResponse, GatewayError> {
            if self.should_fail {
                Err(GatewayError::new(ErrorKind::Network, "fail"))
            } else {
                Ok(ChatCompletionResponse {
                    id: "test".to_string(),
                    object: "chat.completion".to_string(),
                    created: 0,
                    model: "test".to_string(),
                    choices: vec![],
                    usage: Usage { prompt_tokens: 0, completion_tokens: None, total_tokens: None },
                })
            }
        }
        
        // ... other trait methods
    }

    #[tokio::test]
    async fn test_fallback_on_failure() {
        let primary = Arc::new(MockProvider { name: "primary", should_fail: true });
        let fallback = Arc::new(MockProvider { name: "fallback", should_fail: false });
        
        let chain = FallbackChain::new(primary, vec![fallback]);
        let result = chain.execute(|p| async {
            p.chat_completion(ChatCompletionRequest {
                model: "test".to_string(),
                messages: vec![],
                stream: false,
                temperature: None,
                max_tokens: None,
                top_p: None,
                frequency_penalty: None,
                presence_penalty: None,
                stop: None,
                tools: None,
                tool_choice: None,
                user: None,
            }).await
        }).await;
        
        assert!(result.is_ok());
    }
}
```

Run: `cargo test fallback_tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/gateway/fallback.rs tests/unit/fallback_tests.rs
git commit -m "feat: implement fallback chain logic"
```

#### Task 5.5: Gateway Orchestrator

**Files:**
- Create: `src/gateway/mod.rs`

- [ ] **Step 1: Implement gateway orchestrator**

```rust
// src/gateway/mod.rs
use std::collections::HashMap;
use std::sync::Arc;

use crate::config::{AppConfig, ProviderConfig};
use crate::error::{ErrorKind, GatewayError};
use crate::provider::{Provider, openai::OpenAIProvider, anthropic::AnthropicProvider, groq::GroqProvider, ProviderRef};
use crate::types::*;

pub mod fallback;
pub mod key_selector;
pub mod queue;
pub mod retry;

use fallback::FallbackChain;
use key_selector::{select_key, WeightedKey};
use queue::{GatewayRequest, GatewayResponse, ProviderQueue};
use retry::RetryPolicy;

pub struct Gateway {
    providers: HashMap<String, ProviderRef>,
    queues: HashMap<String, ProviderQueue>,
    fallback_chains: HashMap<String, FallbackChain>,
}

impl Gateway {
    pub fn from_config(config: &AppConfig) -> Self {
        let mut providers = HashMap::new();
        let mut queues = HashMap::new();
        
        for (name, provider_config) in &config.providers {
            if !provider_config.enabled {
                continue;
            }
            
            let provider = Self::create_provider(name, provider_config);
            let provider_ref = Arc::new(provider) as ProviderRef;
            
            let keys: Vec<WeightedKey> = provider_config.keys.iter()
                .map(|k| WeightedKey { value: k.value.clone(), weight: k.weight })
                .collect();
            
            let retry_policy = RetryPolicy::new(
                provider_config.max_retries,
                provider_config.retry_backoff_initial_ms,
                provider_config.retry_backoff_max_ms,
            );
            
            let queue = ProviderQueue::new(
                provider_ref.clone(),
                keys,
                retry_policy,
                provider_config.queue_concurrency,
                provider_config.queue_buffer_size,
            );
            
            providers.insert(name.clone(), provider_ref);
            queues.insert(name.clone(), queue);
        }
        
        let mut fallback_chains = HashMap::new();
        for chain_config in &config.fallbacks {
            if let Some(primary) = providers.get(&chain_config.primary) {
                let fallbacks: Vec<ProviderRef> = chain_config.fallbacks.iter()
                    .filter_map(|f| providers.get(f).cloned())
                    .collect();
                
                if !fallbacks.is_empty() {
                    let chain = FallbackChain::new(primary.clone(), fallbacks);
                    fallback_chains.insert(chain_config.primary.clone(), chain);
                }
            }
        }
        
        Self { providers, queues, fallback_chains }
    }
    
    fn create_provider(name: &str, config: &ProviderConfig) -> Box<dyn Provider> {
        // Convert all keys to WeightedKey (stored in provider for dynamic selection)
        let keys: Vec<WeightedKey> = config.keys.iter()
            .map(|k| WeightedKey { value: k.value.clone(), weight: k.weight })
            .collect();
        
        match name {
            "openai" => Box::new(OpenAIProvider::new(
                config.base_url.clone(),
                keys,
                config.request_timeout_seconds,
            )),
            "anthropic" => Box::new(AnthropicProvider::new(
                config.base_url.clone(),
                keys,
                config.request_timeout_seconds,
            )),
            "groq" => Box::new(GroqProvider::new(
                config.base_url.clone(),
                keys,
                config.request_timeout_seconds,
            )),
            _ => panic!("Unknown provider: {}", name),
        }
    }
    
    pub async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        let (provider_name, model) = Self::parse_model(&request.model);
        
        let mut req = request;
        req.model = model;
        
        if let Some(chain) = self.fallback_chains.get(provider_name) {
            chain.execute(|provider| async {
                provider.chat_completion(req.clone()).await
            }).await
        } else {
            let queue = self.queues.get(provider_name)
                .ok_or(GatewayError::new(ErrorKind::NoProviderAvailable, 
                    format!("Provider '{}' not found", provider_name)))?;
            
            let response = queue.send(GatewayRequest::ChatCompletion(req)).await?;
            match response {
                GatewayResponse::ChatCompletion(result) => result,
                _ => Err(GatewayError::new(ErrorKind::Provider, "Unexpected response type")),
            }
        }
    }
    
    pub async fn chat_completion_stream(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<futures::stream::BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
        let (provider_name, model) = Self::parse_model(&request.model);
        
        let mut req = request;
        req.model = model;
        
        let provider = self.providers.get(provider_name)
            .ok_or(GatewayError::new(ErrorKind::NoProviderAvailable, 
                format!("Provider '{}' not found", provider_name)))?;
        
        // NOTE: Stream fallback is not implemented in Plan A.
        // Bifrost supports stream fallback (tryStreamRequest), but it requires
        // complex stream switching logic. This is a known limitation.
        // Stream fallback will be added in v0.2 (Plan B extension).
        provider.chat_completion_stream(req).await
    }
    
    pub async fn embedding(
        &self,
        request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError> {
        let (provider_name, model) = Self::parse_model(&request.model);
        
        let mut req = request;
        req.model = model;
        
        let queue = self.queues.get(provider_name)
            .ok_or(GatewayError::new(ErrorKind::NoProviderAvailable, 
                format!("Provider '{}' not found", provider_name)))?;
        
        let response = queue.send(GatewayRequest::Embedding(req)).await?;
        match response {
            GatewayResponse::Embedding(result) => result,
            _ => Err(GatewayError::new(ErrorKind::Provider, "Unexpected response type")),
        }
    }
    
    pub async fn list_models(&self) -> Result<ModelList, GatewayError> {
        let mut all_models = Vec::new();
        
        for (_, queue) in &self.queues {
            let response = queue.send(GatewayRequest::ListModels).await?;
            if let GatewayResponse::ListModels(Ok(models)) = response {
                all_models.extend(models.data);
            }
        }
        
        Ok(ModelList {
            object: "list".to_string(),
            data: all_models,
        })
    }
    
    fn parse_model(model: &str) -> (&str, String) {
        if let Some(pos) = model.find('/') {
            (&model[..pos], model[pos + 1..].to_string())
        } else {
            ("openai", model.to_string())
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/gateway/mod.rs
git commit -m "feat: implement gateway orchestrator with queues and fallbacks"
```

### Milestone 6: HTTP Server

**Goal:** Actix-web server with routes, handlers, SSE streaming, and middleware.

#### Task 6.1: HTTP Handlers with SSE

**Files:**
- Create: `src/http/handlers.rs`

- [ ] **Step 1: Implement handlers with proper SSE format**

```rust
// src/http/handlers.rs
use actix_web::{web, HttpResponse, Responder};
use futures::stream::{self, StreamExt};
use serde_json::json;

use crate::gateway::Gateway;
use crate::types::*;

pub async fn chat_completions(
    body: web::Json<ChatCompletionRequest>,
    gateway: web::Data<Gateway>,
) -> impl Responder {
    let request = body.into_inner();
    
    if request.stream {
        match gateway.chat_completion_stream(request).await {
            Ok(stream) => {
                let sse_stream = stream
                    .map(|chunk| {
                        match chunk {
                            Ok(chunk) => {
                                let data = serde_json::to_string(&chunk).unwrap();
                                Ok::<_, actix_web::Error>(
                                    format!("data: {}\n\n", data)
                                )
                            }
                            Err(e) => {
                                let data = json!({"error": e.to_string()}).to_string();
                                Ok(format!("data: {}\n\n", data))
                            }
                        }
                    })
                    .chain(stream::once(async {
                        Ok::<_, actix_web::Error>("data: [DONE]\n\n".to_string())
                    }));
                
                HttpResponse::Ok()
                    .content_type("text/event-stream")
                    .insert_header(("Cache-Control", "no-cache"))
                    .insert_header(("Connection", "keep-alive"))
                    .streaming(sse_stream)
            }
            Err(e) => {
                let status = e.status_code();
                HttpResponse::build(actix_web::http::StatusCode::from_u16(status).unwrap())
                    .json(ErrorResponse {
                        error: ApiError {
                            message: e.to_string(),
                            error_type: "server_error".to_string(),
                            code: Some(status.to_string()),
                        }
                    })
            }
        }
    } else {
        match gateway.chat_completion(request).await {
            Ok(response) => HttpResponse::Ok().json(response),
            Err(e) => {
                let status = e.status_code();
                HttpResponse::build(actix_web::http::StatusCode::from_u16(status).unwrap())
                    .json(ErrorResponse {
                        error: ApiError {
                            message: e.to_string(),
                            error_type: "provider_error".to_string(),
                            code: Some(status.to_string()),
                        }
                    })
            }
        }
    }
}

pub async fn embeddings(
    body: web::Json<EmbeddingRequest>,
    gateway: web::Data<Gateway>,
) -> impl Responder {
    match gateway.embedding(body.into_inner()).await {
        Ok(response) => HttpResponse::Ok().json(response),
        Err(e) => {
            let status = e.status_code();
            HttpResponse::build(actix_web::http::StatusCode::from_u16(status).unwrap())
                .json(ErrorResponse {
                    error: ApiError {
                        message: e.to_string(),
                        error_type: "provider_error".to_string(),
                        code: Some(status.to_string()),
                    }
                })
        }
    }
}

pub async fn list_models(
    gateway: web::Data<Gateway>,
) -> impl Responder {
    match gateway.list_models().await {
        Ok(response) => HttpResponse::Ok().json(response),
        Err(e) => {
            HttpResponse::InternalServerError().json(ErrorResponse {
                error: ApiError {
                    message: e.to_string(),
                    error_type: "server_error".to_string(),
                    code: None,
                }
            })
        }
    }
}

pub async fn health() -> impl Responder {
    HttpResponse::Ok().json(json!({"status": "ok"}))
}
```

- [ ] **Step 2: Commit**

```bash
git add src/http/handlers.rs
git commit -m "feat: implement HTTP handlers with SSE streaming"
```

#### Task 6.2: HTTP Middleware and Server

**Files:**
- Create: `src/http/middleware.rs`
- Create: `src/http/mod.rs`

- [ ] **Step 1: Implement middleware**

```rust
// src/http/middleware.rs
use actix_web::middleware::{Logger, NormalizePath};
use actix_web::App;

pub fn configure_app() -> App<impl actix_web::dev::ServiceFactory<actix_web::dev::ServiceRequest>> {
    App::new()
        .wrap(Logger::default())
        .wrap(NormalizePath::trim())
        .wrap(
            actix_cors::Cors::default()
                .allow_any_origin()
                .allow_any_method()
                .allow_any_header()
                .max_age(3600),
        )
}
```

- [ ] **Step 2: Implement server module**

```rust
// src/http/mod.rs
use actix_web::{web, App, HttpServer};

use crate::config::AppConfig;
use crate::gateway::Gateway;

pub mod handlers;
pub mod middleware;

pub async fn run_server(config: &AppConfig, gateway: Gateway) -> std::io::Result<()> {
    let gateway = web::Data::new(gateway);
    let bind_addr = format!("{}:{}", config.server.host, config.server.port);
    
    HttpServer::new(move || {
        middleware::configure_app()
            .app_data(gateway.clone())
            .route("/v1/chat/completions", web::post().to(handlers::chat_completions))
            .route("/v1/embeddings", web::post().to(handlers::embeddings))
            .route("/v1/models", web::get().to(handlers::list_models))
            .route("/health", web::get().to(handlers::health))
    })
    .bind(&bind_addr)?
    .run()
    .await
}
```

- [ ] **Step 3: Update main.rs**

```rust
// Update src/main.rs run() function:
async fn run() -> anyhow::Result<()> {
    let config_path = std::env::var("HEIRLOOM_CONFIG_PATH")
        .unwrap_or_else(|_| "config.toml".to_string());
    
    let config = AppConfig::from_file_with_env(Path::new(&config_path))?;
    config.validate()?;
    
    tracing_subscriber::fmt()
        .with_env_filter(&config.server.log_level)
        .init();
    
    info!("Loading configuration from {}", config_path);
    info!("Starting Heirloom on {}:{}", config.server.host, config.server.port);
    
    let gateway = Gateway::from_config(&config);
    
    http::run_server(&config, gateway).await?;
    
    Ok(())
}
```

- [ ] **Step 4: Build project**

Run: `cargo build`
Expected: Compiles successfully

- [ ] **Step 5: Commit**

```bash
git add src/http/ src/main.rs
git commit -m "feat: implement HTTP server with actix-web"
```

### Milestone 7: Docker and Deployment

**Goal:** Docker image, health checks, example configuration.

#### Task 7.1: Dockerfile and Example Config

**Files:**
- Create: `Dockerfile`
- Create: `config.example.toml`

- [ ] **Step 1: Create Dockerfile**

```dockerfile
# Build stage
FROM rust:1.75-slim as builder
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY src ./src
COPY tests ./tests
RUN cargo build --release

# Runtime stage
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y \
    ca-certificates \
    wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/heirloom /usr/local/bin/heirloom

# Create non-root user
RUN useradd -m -u 1000 heirloom
USER heirloom

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["heirloom"]
CMD ["--config", "/etc/heirloom/config.toml"]
```

- [ ] **Step 2: Create example config**

```toml
# config.example.toml
[server]
host = "0.0.0.0"
port = 8080
log_level = "info"

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [
    { value = "${OPENAI_API_KEY}", weight = 1.0 }
]
max_retries = 3
retry_backoff_initial_ms = 500
retry_backoff_max_ms = 5000
request_timeout_seconds = 30
queue_concurrency = 100
queue_buffer_size = 1000

[providers.anthropic]
enabled = true
base_url = "https://api.anthropic.com"
keys = [
    { value = "${ANTHROPIC_API_KEY}", weight = 1.0 }
]

[providers.groq]
enabled = true
base_url = "https://api.groq.com/openai/v1"
keys = [
    { value = "${GROQ_API_KEY}", weight = 1.0 }
]

[fallbacks]
chains = [
    { primary = "openai/gpt-4", fallbacks = ["anthropic/claude-3-opus-20240229", "groq/llama-3-70b-8192"] }
]
```

- [ ] **Step 3: Test Docker build**

Run: `docker build -t heirloom:0.1.0 .`
Expected: Builds successfully

- [ ] **Step 4: Commit**

```bash
git add Dockerfile config.example.toml
git commit -m "feat: add Dockerfile and example configuration"
```

---

## Summary

### Milestones (7)

| # | Milestone | Tasks | Description |
|---|-----------|-------|-------------|
| 1 | Project Skeleton & Config | 2 | Cargo, TOML config with validation |
| 2 | Core Types | 2 | OpenAI-compatible request/response types |
| 3 | Error & Context | 2 | Structured errors, request context |
| 4 | Providers | 5 | Trait + OpenAI + Anthropic + Groq |
| 5 | Gateway Core | 5 | Queues, retry, fallback, key selector, orchestrator |
| 6 | HTTP Server | 2 | Actix-web, SSE streaming |
| 7 | Docker & Deploy | 1 | Dockerfile, health checks |

### Total Tasks: 19
### Estimated Time: 6-8 weeks

### Key Design Decisions (Aligned with Bifrost)

1. **Worker 职责范围**: Key 选择 + 重试在 worker 内部完成（参考 `requestWorker`）
2. **ProviderQueue 生命周期**: `shutdown()` + `drain()` + atomic `closing` flag（参考 `ProviderQueue`）
3. **配置验证**: `CheckAndSetDefaults` 模式，启动时验证
4. **错误上下文**: 结构化错误，包含 provider/model/retry_count/fallback_index（参考 `BifrostError.ExtraFields`）
5. **请求上下文**: `RequestContext` 替代 `BifrostContext`（简化版）

### Known Limitations (v0.1)

| Limitation | Description | Planned For |
|-----------|-------------|-------------|
| **Stream Fallback** | Streaming requests do not support provider fallback. If the primary provider fails mid-stream, the request fails. | v0.2 |
| **Anthropic Streaming** | Anthropic provider does not support streaming in v0.1 | v0.2 |
| **Anthropic Embeddings** | Anthropic provider does not support embeddings in v0.1 | v0.2 |
| **Config Hot-Reload** | Configuration changes require restart | v0.3 |

---

**Plan saved to:** `docs/superpowers/plans/2025-04-27-heirloom-core-gateway-plan.md`

**Next:** Review this plan, then proceed to execution (Subagent-Driven or Inline).
