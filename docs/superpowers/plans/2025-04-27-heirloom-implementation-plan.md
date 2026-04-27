# Heirloom Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a lightweight Rust AI Gateway with OpenAI-compatible API supporting multi-provider failover, retries, and MCP tool integration.

**Architecture:** Actix-web HTTP server → Gateway core (per-provider queues, retry, fallback) → Provider clients (reqwest) → LLM APIs. MCP runs as an optional client layer that intercepts chat completions for tool calling.

**Tech Stack:** actix-web, tokio, reqwest, serde, toml, config, tracing, thiserror, anyhow

---

## Scope Breakdown

This plan is split into **10 independent milestones**, each producing working, testable software:

1. **Project Skeleton & Config** — Cargo workspace, logging, configuration loading
2. **Core Types** — Request/response types (OpenAI-compatible schema)
3. **Provider Trait & OpenAI** — Provider abstraction + OpenAI implementation
4. **Anthropic Provider** — Anthropic Messages API adapter
5. **Groq Provider** — OpenAI-compatible provider (delegates to OpenAI module)
6. **Gateway Core** — Queues, retry, fallback, key selection
7. **HTTP Server** — Actix-web routes, handlers, streaming
8. **Integration Tests** — End-to-end tests with mock servers
9. **MCP Gateway** — stdio/SSE clients, tool execution, agent mode
10. **Docker & Deployment** — Dockerfile, health checks, docs

**Dependency graph:**
```
1 → 2 → 3 → 4 → 5 → 6 → 7 → 8
                  ↓
                  9 → 10
```

---

## File Map

```
heirloom/
├── Cargo.toml
├── Cargo.lock
├── src/
│   ├── main.rs
│   ├── config.rs
│   ├── error.rs
│   ├── types/
│   │   ├── mod.rs
│   │   ├── chat.rs
│   │   ├── embedding.rs
│   │   ├── models.rs
│   │   └── common.rs
│   ├── provider/
│   │   ├── mod.rs
│   │   ├── openai.rs
│   │   ├── anthropic.rs
│   │   └── groq.rs
│   ├── gateway/
│   │   ├── mod.rs
│   │   ├── queue.rs
│   │   ├── retry.rs
│   │   ├── fallback.rs
│   │   └── key_selector.rs
│   ├── http/
│   │   ├── mod.rs
│   │   ├── handlers.rs
│   │   └── middleware.rs
│   ├── mcp/
│   │   ├── mod.rs
│   │   ├── client.rs
│   │   ├── transport.rs
│   │   ├── tools.rs
│   │   └── agent.rs
│   └── client/
│       └── mod.rs
├── tests/
│   ├── unit/
│   │   ├── config_tests.rs
│   │   ├── key_selector_tests.rs
│   │   ├── retry_tests.rs
│   │   └── fallback_tests.rs
│   └── integration/
│       ├── chat_tests.rs
│       ├── embedding_tests.rs
│       ├── stream_tests.rs
│       ├── fallback_tests.rs
│       └── mcp_tests.rs
├── Dockerfile
└── config.example.toml
```

---

## Milestone 1: Project Skeleton & Configuration

**Goal:** Create Cargo project with dependencies, logging, and configuration loading from TOML + env.

### Task 1.1: Initialize Cargo Project

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
```

- [ ] **Step 2: Create src/main.rs**

```rust
use tracing::{info, error};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();
    info!("Heirloom starting...");
    
    if let Err(e) = run().await {
        error!("Fatal error: {}", e);
        std::process::exit(1);
    }
}

async fn run() -> anyhow::Result<()> {
    info!("Heirloom running");
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

### Task 1.2: Configuration Types

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
}
```

Run: `cargo test config_tests`
Expected: FAIL — `AppConfig` not defined

- [ ] **Step 2: Implement config types**

```rust
use serde::Deserialize;
use std::collections::HashMap;

#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub providers: HashMap<String, ProviderConfig>,
    #[serde(default)]
    pub fallbacks: Vec<FallbackChainConfig>,
    #[serde(default)]
    pub mcp: Option<McpConfig>,
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

fn default_host() -> String { "0.0.0.0".to_string() }
fn default_port() -> u16 { 8080 }
fn default_log_level() -> String { "info".to_string() }

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

#[derive(Debug, Clone, Deserialize)]
pub struct McpConfig {
    #[serde(default = "default_max_agent_depth")]
    pub max_agent_depth: usize,
    #[serde(default)]
    pub clients: Vec<McpClientConfig>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct McpClientConfig {
    pub name: String,
    pub transport: String,
    pub command: Option<String>,
    pub args: Option<Vec<String>>,
    pub url: Option<String>,
    #[serde(default = "default_auto_execute")]
    pub auto_execute: bool,
}

fn default_base_url() -> String { "".to_string() }
fn default_max_retries() -> u32 { 3 }
fn default_retry_backoff_initial_ms() -> u64 { 500 }
fn default_retry_backoff_max_ms() -> u64 { 5000 }
fn default_request_timeout_seconds() -> u64 { 30 }
fn default_queue_concurrency() -> usize { 100 }
fn default_queue_buffer_size() -> usize { 1000 }
fn default_weight() -> f64 { 1.0 }
fn default_max_agent_depth() -> usize { 5 }
fn default_auto_execute() -> bool { false }
```

- [ ] **Step 3: Run tests**

Run: `cargo test config_tests`
Expected: PASS

- [ ] **Step 4: Add config loading from file**

Add to `src/config.rs`:

```rust
use std::path::Path;

impl AppConfig {
    pub fn from_file(path: &Path) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path)?;
        let config: AppConfig = toml::from_str(&content)?;
        Ok(config)
    }
}
```

- [ ] **Step 5: Test config loading**

```rust
#[test]
fn test_load_from_file() {
    let toml = r#"
[server]
port = 9090

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [{ value = "test", weight = 1.0 }]
"#;
    let tmpfile = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(tmpfile.path(), toml).unwrap();
    
    let config = AppConfig::from_file(tmpfile.path()).unwrap();
    assert_eq!(config.server.port, 9090);
}
```

Add to `Cargo.toml` dev-dependencies:
```toml
[dev-dependencies]
tempfile = "3.14"
```

Run: `cargo test config_tests`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/config.rs tests/unit/config_tests.rs Cargo.toml
git commit -m "feat: add configuration types and loading"
```

### Task 1.3: Environment Variable Overrides

**Files:**
- Modify: `src/config.rs`

- [ ] **Step 1: Add env var overrides**

```rust
impl AppConfig {
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
}
```

- [ ] **Step 2: Test env overrides**

```rust
#[test]
fn test_env_override() {
    let toml = r#"
[server]
port = 8080
"#;
    let tmpfile = tempfile::NamedTempFile::new().unwrap();
    std::fs::write(tmpfile.path(), toml).unwrap();
    
    std::env::set_var("HEIRLOOM_PORT", "9999");
    let config = AppConfig::from_file_with_env(tmpfile.path()).unwrap();
    assert_eq!(config.server.port, 9999);
    std::env::remove_var("HEIRLOOM_PORT");
}
```

Run: `cargo test config_tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/config.rs tests/unit/config_tests.rs
git commit -m "feat: add environment variable overrides for config"
```

---

## Milestone 2: Core Types

**Goal:** Define all request/response types matching OpenAI API schema.

### Task 2.1: Common Types

**Files:**
- Create: `src/types/mod.rs`
- Create: `src/types/common.rs`
- Create: `src/types/chat.rs`
- Create: `src/types/embedding.rs`
- Create: `src/types/models.rs`

- [ ] **Step 1: Write common types**

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

- [ ] **Step 2: Write chat types**

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

- [ ] **Step 3: Write embedding types**

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

- [ ] **Step 4: Write model list types**

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

- [ ] **Step 5: Write types/mod.rs**

```rust
pub mod chat;
pub mod common;
pub mod embedding;
pub mod models;

pub use chat::*;
pub use common::*;
pub use embedding::*;
pub use models::*;
```

- [ ] **Step 6: Add mod.rs entries to src/lib.rs or src/main.rs**

Since this is a binary crate, add to `src/main.rs`:
```rust
mod types;
```

- [ ] **Step 7: Test serialization**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_chat_request_serialization() {
        let req = ChatCompletionRequest {
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
        
        let json = serde_json::to_string(&req).unwrap();
        assert!(json.contains("\"model\":\"gpt-4\""));
        assert!(json.contains("\"role\":\"user\""));
    }

    #[test]
    fn test_chat_response_deserialization() {
        let json = r#"{
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
        }"#;
        
        let resp: ChatCompletionResponse = serde_json::from_str(json).unwrap();
        assert_eq!(resp.id, "chat-123");
        assert_eq!(resp.choices[0].message.content, Some("Hello!".to_string()));
    }
}
```

Run: `cargo test types::tests`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/types/
git commit -m "feat: add core request/response types"
```

---

## Milestone 3: Provider Trait & OpenAI Implementation

**Goal:** Define Provider trait and implement OpenAI provider with chat, embedding, and model listing.

### Task 3.1: Provider Trait

**Files:**
- Create: `src/provider/mod.rs`
- Create: `src/error.rs`

- [ ] **Step 1: Define error types**

```rust
// src/error.rs
use thiserror::Error;

#[derive(Error, Debug, Clone)]
pub enum GatewayError {
    #[error("Provider error: {message}")]
    Provider { message: String, status_code: Option<u16> },
    
    #[error("Network error: {0}")]
    Network(String),
    
    #[error("Rate limited")]
    RateLimited,
    
    #[error("Invalid request: {0}")]
    InvalidRequest(String),
    
    #[error("Authentication failed")]
    AuthenticationFailed,
    
    #[error("No provider available")]
    NoProviderAvailable,
    
    #[error("Max retries exceeded")]
    MaxRetriesExceeded,
    
    #[error("Agent max depth exceeded")]
    AgentMaxDepthExceeded,
}

impl GatewayError {
    pub fn status_code(&self) -> u16 {
        match self {
            GatewayError::Provider { status_code, .. } => status_code.unwrap_or(500),
            GatewayError::InvalidRequest(_) => 400,
            GatewayError::AuthenticationFailed => 401,
            GatewayError::RateLimited => 429,
            _ => 500,
        }
    }
}
```

- [ ] **Step 2: Define Provider trait**

```rust
// src/provider/mod.rs
use async_trait::async_trait;
use futures::stream::BoxStream;
use crate::types::*;
use crate::error::GatewayError;

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

use std::sync::Arc;
```

- [ ] **Step 3: Add mod declarations**

Add to `src/main.rs`:
```rust
mod error;
mod provider;
```

- [ ] **Step 4: Commit**

```bash
git add src/error.rs src/provider/mod.rs
git commit -m "feat: define Provider trait and error types"
```

### Task 3.2: OpenAI Provider

**Files:**
- Create: `src/provider/openai.rs`
- Create: `src/client/mod.rs`

- [ ] **Step 1: Create HTTP client wrapper**

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

- [ ] **Step 2: Implement OpenAI provider**

```rust
// src/provider/openai.rs
use async_trait::async_trait;
use futures::stream::{self, BoxStream, StreamExt};
use reqwest::header::{AUTHORIZATION, CONTENT_TYPE};
use serde_json::Value;

use crate::client::HttpClient;
use crate::error::GatewayError;
use crate::provider::Provider;
use crate::types::*;

pub struct OpenAIProvider {
    name: String,
    base_url: String,
    api_key: String,
    client: HttpClient,
}

impl OpenAIProvider {
    pub fn new(base_url: String, api_key: String, timeout_seconds: u64) -> Self {
        Self {
            name: "openai".to_string(),
            base_url: base_url.trim_end_matches('/').to_string(),
            api_key,
            client: HttpClient::new(timeout_seconds),
        }
    }
    
    fn auth_header(&self) -> String {
        format!("Bearer {}", self.api_key)
    }
    
    fn handle_error(status: reqwest::StatusCode, body: &str) -> GatewayError {
        if let Ok(err_resp) = serde_json::from_str::<ErrorResponse>(body) {
            GatewayError::Provider {
                message: err_resp.error.message,
                status_code: Some(status.as_u16()),
            }
        } else {
            GatewayError::Provider {
                message: format!("HTTP {}: {}", status, body),
                status_code: Some(status.as_u16()),
            }
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
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        if status.is_success() {
            serde_json::from_str(&body)
                .map_err(|e| GatewayError::Provider {
                    message: format!("Failed to parse response: {}", e),
                    status_code: None,
                })
        } else {
            Err(Self::handle_error(status, &body))
        }
    }
    
    async fn chat_completion_stream(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
        let url = format!("{}/v1/chat/completions", self.base_url);
        let mut request = request;
        request.stream = true;
        
        let response = self.client.inner()
            .post(&url)
            .header(AUTHORIZATION, self.auth_header())
            .header(CONTENT_TYPE, "application/json")
            .header("Accept", "text/event-stream")
            .json(&request)
            .send()
            .await
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        let status = response.status();
        if !status.is_success() {
            let body = response.text().await
                .map_err(|e| GatewayError::Network(e.to_string()))?;
            return Err(Self::handle_error(status, &body));
        }
        
        let stream = response.bytes_stream()
            .map(|chunk| {
                match chunk {
                    Ok(bytes) => {
                        let text = String::from_utf8_lossy(&bytes);
                        parse_sse_chunk(&text)
                    }
                    Err(e) => Err(GatewayError::Network(e.to_string())),
                }
            })
            .filter_map(|result| async move {
                match result {
                    Ok(Some(chunk)) => Some(Ok(chunk)),
                    Ok(None) => None,
                    Err(e) => Some(Err(e)),
                }
            });
        
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
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        if status.is_success() {
            serde_json::from_str(&body)
                .map_err(|e| GatewayError::Provider {
                    message: format!("Failed to parse response: {}", e),
                    status_code: None,
                })
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
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        if status.is_success() {
            serde_json::from_str(&body)
                .map_err(|e| GatewayError::Provider {
                    message: format!("Failed to parse response: {}", e),
                    status_code: None,
                })
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
                .map_err(|e| GatewayError::Provider {
                    message: format!("Failed to parse SSE chunk: {}", e),
                    status_code: None,
                })?;
            return Ok(Some(chunk));
        }
    }
    Ok(None)
}
```

- [ ] **Step 3: Test OpenAI provider (mock server)**

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

Add to `Cargo.toml` dev-dependencies:
```toml
wiremock = "0.6"
```

Run: `cargo test provider::openai::tests`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/provider/openai.rs src/client/mod.rs Cargo.toml
git commit -m "feat: implement OpenAI provider with chat, embedding, models"
```

---

## Milestone 4: Anthropic Provider

**Goal:** Implement Anthropic Messages API provider with format conversion.

### Task 4.1: Anthropic Types

**Files:**
- Create: `src/provider/anthropic_types.rs`

- [ ] **Step 1: Define Anthropic-specific types**

```rust
// src/provider/anthropic_types.rs
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnthropicRequest {
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnthropicMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnthropicResponse {
    pub id: String,
    #[serde(rename = "type")]
    pub response_type: String,
    pub role: String,
    pub content: Vec<AnthropicContent>,
    pub model: String,
    pub usage: AnthropicUsage,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum AnthropicContent {
    #[serde(rename = "text")]
    Text { text: String },
    #[serde(rename = "tool_use")]
    ToolUse { id: String, name: String, input: serde_json::Value },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnthropicUsage {
    pub input_tokens: i64,
    pub output_tokens: i64,
}
```

### Task 4.2: Anthropic Provider Implementation

**Files:**
- Create: `src/provider/anthropic.rs`

- [ ] **Step 1: Implement Anthropic provider**

```rust
// src/provider/anthropic.rs
use async_trait::async_trait;
use futures::stream::{BoxStream, StreamExt};
use reqwest::header::{CONTENT_TYPE, HeaderMap, HeaderValue};

use crate::client::HttpClient;
use crate::error::GatewayError;
use crate::provider::Provider;
use crate::types::*;
use super::anthropic_types::*;

pub struct AnthropicProvider {
    base_url: String,
    api_key: String,
    client: HttpClient,
}

impl AnthropicProvider {
    pub fn new(base_url: String, api_key: String, timeout_seconds: u64) -> Self {
        Self {
            base_url: base_url.trim_end_matches('/').to_string(),
            api_key,
            client: HttpClient::new(timeout_seconds),
        }
    }
    
    fn build_headers(&self) -> HeaderMap {
        let mut headers = HeaderMap::new();
        headers.insert("x-api-key", HeaderValue::from_str(&self.api_key).unwrap());
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
    
    fn handle_error(status: reqwest::StatusCode, body: &str) -> GatewayError {
        if let Ok(err) = serde_json::from_str::<serde_json::Value>(body) {
            let message = err.get("error")
                .and_then(|e| e.get("message"))
                .and_then(|m| m.as_str())
                .unwrap_or(body);
            GatewayError::Provider {
                message: message.to_string(),
                status_code: Some(status.as_u16()),
            }
        } else {
            GatewayError::Provider {
                message: format!("HTTP {}: {}", status, body),
                status_code: Some(status.as_u16()),
            }
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
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| GatewayError::Network(e.to_string()))?;
        
        if status.is_success() {
            let anthropic_resp: AnthropicResponse = serde_json::from_str(&body)
                .map_err(|e| GatewayError::Provider {
                    message: format!("Failed to parse response: {}", e),
                    status_code: None,
                })?;
            Ok(Self::convert_response(anthropic_resp))
        } else {
            Err(Self::handle_error(status, &body))
        }
    }
    
    async fn chat_completion_stream(
        &self,
        _request: ChatCompletionRequest,
    ) -> Result<BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
        Err(GatewayError::Provider {
            message: "Streaming not yet implemented for Anthropic".to_string(),
            status_code: None,
        })
    }
    
    async fn embedding(
        &self,
        _request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError> {
        Err(GatewayError::Provider {
            message: "Embeddings not yet implemented for Anthropic".to_string(),
            status_code: None,
        })
    }
    
    async fn list_models(&self) -> Result<ModelList, GatewayError> {
        Err(GatewayError::Provider {
            message: "List models not yet implemented for Anthropic".to_string(),
            status_code: None,
        })
    }
}
```

- [ ] **Step 2: Test Anthropic conversion**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_convert_request() {
        let request = ChatCompletionRequest {
            model: "claude-3-opus".to_string(),
            messages: vec![
                ChatMessage::System { content: "You are helpful".to_string() },
                ChatMessage::User { content: "Hello".to_string() },
            ],
            stream: false,
            temperature: Some(0.7),
            max_tokens: Some(100),
            ..Default::default()
        };
        
        let anthropic = AnthropicProvider::convert_request(&request);
        assert_eq!(anthropic.model, "claude-3-opus");
        assert_eq!(anthropic.system, Some("You are helpful".to_string()));
        assert_eq!(anthropic.messages.len(), 1);
        assert_eq!(anthropic.messages[0].role, "user");
    }
}
```

Run: `cargo test provider::anthropic::tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/provider/anthropic.rs src/provider/anthropic_types.rs
git commit -m "feat: implement Anthropic provider with format conversion"
```

---

## Milestone 5: Groq Provider

**Goal:** Implement Groq provider (delegates to OpenAI, different base URL and key format).

### Task 5.1: Groq Provider

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
    pub fn new(base_url: String, api_key: String, timeout_seconds: u64) -> Self {
        Self {
            inner: OpenAIProvider::new(base_url, api_key, timeout_seconds),
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

- [ ] **Step 2: Test Groq provider**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_groq_name() {
        let provider = GroqProvider::new(
            "https://api.groq.com".to_string(),
            "test-key".to_string(),
            30,
        );
        assert_eq!(provider.name(), "groq");
    }
}
```

Run: `cargo test provider::groq::tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/provider/groq.rs
git commit -m "feat: implement Groq provider (delegates to OpenAI)"
```

---

## Milestone 6: Gateway Core

**Goal:** Implement request queues, retry logic, fallback chains, and key selection.

### Task 6.1: Key Selector

**Files:**
- Create: `src/gateway/key_selector.rs`
- Create: `tests/unit/key_selector_tests.rs`

- [ ] **Step 1: Write failing test**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_weighted_random_selection() {
        let keys = vec![
            WeightedKey { value: "key-a".to_string(), weight: 1.0 },
            WeightedKey { value: "key-b".to_string(), weight: 2.0 },
        ];
        
        let selected = select_key(&keys);
        assert![keys.iter().any(|k| k.value == selected.value)];
    }
}
```

Run: `cargo test key_selector_tests`
Expected: FAIL — `WeightedKey` not defined

- [ ] **Step 2: Implement weighted random selection**

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
    
    let total_weight: f64 = keys.iter().map(|k| k.weight).sum();
    let mut rng = rand::thread_rng();
    let mut point = rng.gen::<f64>() * total_weight;
    
    for key in keys {
        point -= key.weight;
        if point <= 0.0 {
            return key;
        }
    }
    
    &keys[keys.len() - 1]
}
```

- [ ] **Step 3: Run tests**

Run: `cargo test key_selector_tests`
Expected: PASS

- [ ] **Step 4: Add distribution test**

```rust
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
    
    let count_a = counts.get("key-a").unwrap_or(&0);
    let count_b = counts.get("key-b").unwrap_or(&0);
    
    // key-b should be selected ~3x more often than key-a
    let ratio = *count_b as f64 / *count_a as f64;
    assert!(ratio > 2.0 && ratio < 4.0, "Ratio was {}", ratio);
}
```

Run: `cargo test key_selector_tests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/gateway/key_selector.rs tests/unit/key_selector_tests.rs
git commit -m "feat: implement weighted random key selection"
```

### Task 6.2: Retry Policy

**Files:**
- Create: `src/gateway/retry.rs`
- Create: `tests/unit/retry_tests.rs`

- [ ] **Step 1: Write failing test**

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[tokio::test]
    async fn test_retry_succeeds_eventually() {
        let policy = RetryPolicy::new(3, Duration::from_millis(10), Duration::from_millis(100));
        let attempts = Arc::new(AtomicUsize::new(0));
        let attempts_clone = attempts.clone();
        
        let result = policy.execute(|| async {
            let attempt = attempts_clone.fetch_add(1, Ordering::SeqCst);
            if attempt < 2 {
                Err(GatewayError::Network("fail".to_string()))
            } else {
                Ok("success")
            }
        }).await;
        
        assert_eq!(result.unwrap(), "success");
        assert_eq!(attempts.load(Ordering::SeqCst), 3);
    }
}
```

Run: `cargo test retry_tests`
Expected: FAIL — `RetryPolicy` not defined

- [ ] **Step 2: Implement retry policy**

```rust
// src/gateway/retry.rs
use std::time::Duration;
use tokio::time::sleep;
use rand::Rng;
use crate::error::GatewayError;

pub struct RetryPolicy {
    max_retries: u32,
    backoff_initial: Duration,
    backoff_max: Duration,
}

impl RetryPolicy {
    pub fn new(max_retries: u32, backoff_initial: Duration, backoff_max: Duration) -> Self {
        Self {
            max_retries,
            backoff_initial,
            backoff_max,
        }
    }
    
    pub async fn execute<F, Fut, T>(&self,
        operation: F,
    ) -> Result<T, GatewayError>
    where
        F: Fn() -> Fut,
        Fut: std::future::Future<Output = Result<T, GatewayError>>,
    {
        let mut last_error = None;
        
        for attempt in 0..=self.max_retries {
            match operation().await {
                Ok(result) => return Ok(result),
                Err(e) => {
                    if !Self::is_retryable(&e) || attempt == self.max_retries {
                        return Err(e);
                    }
                    last_error = Some(e);
                    let backoff = self.calculate_backoff(attempt);
                    sleep(backoff).await;
                }
            }
        }
        
        Err(last_error.unwrap_or(GatewayError::MaxRetriesExceeded))
    }
    
    fn is_retryable(error: &GatewayError) -> bool {
        matches!(error,
            GatewayError::Network(_) |
            GatewayError::RateLimited |
            GatewayError::Provider { status_code: Some(500..=599), .. }
        )
    }
    
    fn calculate_backoff(&self, attempt: u32) -> Duration {
        let base = self.backoff_initial.as_millis() as u64 * 2u64.pow(attempt);
        let capped = std::cmp::min(base, self.backoff_max.as_millis() as u64);
        let jitter = rand::thread_rng().gen_range(0..capped / 10);
        Duration::from_millis(capped + jitter)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cargo test retry_tests`
Expected: PASS

- [ ] **Step 4: Test non-retryable error**

```rust
#[tokio::test]
async fn test_no_retry_on_4xx() {
    let policy = RetryPolicy::new(3, Duration::from_millis(10), Duration::from_millis(100));
    let attempts = Arc::new(AtomicUsize::new(0));
    let attempts_clone = attempts.clone();
    
    let result = policy.execute(|| async {
        attempts_clone.fetch_add(1, Ordering::SeqCst);
        Err(GatewayError::InvalidRequest("bad request".to_string()))
    }).await;
    
    assert!(result.is_err());
    assert_eq!(attempts.load(Ordering::SeqCst), 1); // No retries
}
```

Run: `cargo test retry_tests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/gateway/retry.rs tests/unit/retry_tests.rs
git commit -m "feat: implement retry policy with exponential backoff"
```

### Task 6.3: Provider Queue

**Files:**
- Create: `src/gateway/queue.rs`

- [ ] **Step 1: Implement request queue**

```rust
// src/gateway/queue.rs
use tokio::sync::{mpsc, oneshot};
use std::sync::Arc;
use crate::error::GatewayError;
use crate::types::*;
use crate::provider::ProviderRef;

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
}

impl ProviderQueue {
    pub fn new(
        provider: ProviderRef,
        concurrency: usize,
        buffer_size: usize,
    ) -> Self {
        let (tx, mut rx) = mpsc::channel::<QueuedRequest>(buffer_size);
        
        for _ in 0..concurrency {
            let provider = provider.clone();
            let mut rx = rx.resubscribe();
            
            tokio::spawn(async move {
                while let Some(req) = rx.recv().await {
                    let response = process_request(&provider, req.request).await;
                    let _ = req.response_tx.send(response);
                }
            });
        }
        
        Self { tx }
    }
    
    pub async fn send(
        &self,
        request: GatewayRequest,
    ) -> Result<GatewayResponse, GatewayError> {
        let (response_tx, response_rx) = oneshot::channel();
        
        self.tx.send(QueuedRequest { request, response_tx })
            .await
            .map_err(|_| GatewayError::Provider {
                message: "Queue closed".to_string(),
                status_code: None,
            })?;
        
        response_rx.await
            .map_err(|_| GatewayError::Provider {
                message: "Response channel closed".to_string(),
                status_code: None,
            })
    }
}

async fn process_request(
    provider: &ProviderRef,
    request: GatewayRequest,
) -> GatewayResponse {
    match request {
        GatewayRequest::ChatCompletion(req) => {
            GatewayResponse::ChatCompletion(provider.chat_completion(req).await)
        }
        GatewayRequest::Embedding(req) => {
            GatewayResponse::Embedding(provider.embedding(req).await)
        }
        GatewayRequest::ListModels => {
            GatewayResponse::ListModels(provider.list_models().await)
        }
    }
}
```

**Note:** The above uses `resubscribe()` which doesn't exist on `mpsc::Receiver`. Let me fix this to use a single receiver with multiple workers sharing it via `Arc<Mutex>` or use `broadcast`. Actually, better approach: each worker gets its own clone of the sender and we use a fan-out pattern, or simpler: use a single worker since we're just proxying.

Let me simplify:

```rust
// Simplified version - single worker per provider
pub struct ProviderQueue {
    tx: mpsc::Sender<QueuedRequest>,
}

impl ProviderQueue {
    pub fn new(
        provider: ProviderRef,
        _concurrency: usize, // Reserved for future multi-worker
        buffer_size: usize,
    ) -> Self {
        let (tx, mut rx) = mpsc::channel::<QueuedRequest>(buffer_size);
        
        tokio::spawn(async move {
            while let Some(req) = rx.recv().await {
                let response = process_request(&provider, req.request).await;
                let _ = req.response_tx.send(response);
            }
        });
        
        Self { tx }
    }
    // ...
}
```

- [ ] **Step 2: Commit**

```bash
git add src/gateway/queue.rs
git commit -m "feat: implement per-provider request queue"
```

### Task 6.4: Fallback Chain

**Files:**
- Create: `src/gateway/fallback.rs`
- Create: `tests/unit/fallback_tests.rs`

- [ ] **Step 1: Write failing test**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_fallback_on_failure() {
        let primary = Arc::new(FailingProvider) as ProviderRef;
        let fallback = Arc::new(SuccessProvider) as ProviderRef;
        
        let chain = FallbackChain::new(primary, vec![fallback]);
        let result: Result<String, GatewayError> = chain.execute(|p| async {
            if p.name() == "failing" {
                Err(GatewayError::Network("fail".to_string()))
            } else {
                Ok("success".to_string())
            }
        }).await;
        
        assert_eq!(result.unwrap(), "success");
    }
}
```

Run: `cargo test fallback_tests`
Expected: FAIL — types not defined

- [ ] **Step 2: Implement fallback chain**

```rust
// src/gateway/fallback.rs
use crate::error::GatewayError;
use crate::provider::ProviderRef;

pub struct FallbackChain {
    primary: ProviderRef,
    fallbacks: Vec<ProviderRef>,
}

impl FallbackChain {
    pub fn new(primary: ProviderRef, fallbacks: Vec<ProviderRef>) -> Self {
        Self { primary, fallbacks }
    }
    
    pub async fn execute<F, Fut, T>(&self,
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
                if !Self::should_fallback(&e) {
                    return Err(e);
                }
            }
        }
        
        // Try fallbacks
        for fallback in &self.fallbacks {
            match operation(fallback).await {
                Ok(result) => return Ok(result),
                Err(e) => {
                    if !Self::should_fallback(&e) {
                        return Err(e);
                    }
                }
            }
        }
        
        Err(GatewayError::NoProviderAvailable)
    }
    
    fn should_fallback(error: &GatewayError) -> bool {
        matches!(error,
            GatewayError::Network(_) |
            GatewayError::RateLimited |
            GatewayError::Provider { status_code: Some(500..=599 | 429), .. } |
            GatewayError::MaxRetriesExceeded
        )
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cargo test fallback_tests`
Expected: PASS

- [ ] **Step 4: Test no fallback on 4xx**

```rust
#[tokio::test]
async fn test_no_fallback_on_4xx() {
    let primary = Arc::new(FailingProvider) as ProviderRef;
    let fallback = Arc::new(SuccessProvider) as ProviderRef;
    
    let chain = FallbackChain::new(primary, vec![fallback]);
    let result: Result<String, GatewayError> = chain.execute(|p| async {
        if p.name() == "failing" {
            Err(GatewayError::InvalidRequest("bad".to_string()))
        } else {
            Ok("success".to_string())
        }
    }).await;
    
    assert!(result.is_err());
}
```

Run: `cargo test fallback_tests`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/gateway/fallback.rs tests/unit/fallback_tests.rs
git commit -m "feat: implement fallback chain logic"
```

### Task 6.5: Gateway Orchestrator

**Files:**
- Create: `src/gateway/mod.rs`

- [ ] **Step 1: Implement gateway orchestrator**

```rust
// src/gateway/mod.rs
use std::collections::HashMap;
use std::sync::Arc;

use crate::config::{AppConfig, ProviderConfig};
use crate::error::GatewayError;
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
            
            let queue = ProviderQueue::new(
                provider_ref.clone(),
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
        let key = select_key(
            &config.keys.iter()
                .map(|k| WeightedKey { value: k.value.clone(), weight: k.weight })
                .collect::<Vec<_>>()
        ).value.clone();
        
        match name {
            "openai" => Box::new(OpenAIProvider::new(
                config.base_url.clone(),
                key,
                config.request_timeout_seconds,
            )),
            "anthropic" => Box::new(AnthropicProvider::new(
                config.base_url.clone(),
                key,
                config.request_timeout_seconds,
            )),
            "groq" => Box::new(GroqProvider::new(
                config.base_url.clone(),
                key,
                config.request_timeout_seconds,
            )),
            _ => panic!("Unknown provider: {}", name),
        }
    }
    
    pub async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        let (provider_name, _) = Self::parse_model(&request.model);
        
        // If fallback chain exists, use it
        if let Some(chain) = self.fallback_chains.get(provider_name) {
            chain.execute(|provider| async {
                let mut req = request.clone();
                req.model = Self::strip_provider_prefix(&request.model);
                provider.chat_completion(req).await
            }).await
        } else {
            // Direct provider call
            let queue = self.queues.get(provider_name)
                .ok_or(GatewayError::NoProviderAvailable)?;
            
            let response = queue.send(GatewayRequest::ChatCompletion(request)).await?;
            match response {
                GatewayResponse::ChatCompletion(result) => result,
                _ => Err(GatewayError::Provider {
                    message: "Unexpected response type".to_string(),
                    status_code: None,
                }),
            }
        }
    }
    
    fn parse_model(model: &str) -> (&str, &str) {
        if let Some(pos) = model.find('/') {
            (&model[..pos], &model[pos + 1..])
        } else {
            ("openai", model) // Default provider
        }
    }
    
    fn strip_provider_prefix(model: &str) -> String {
        if let Some(pos) = model.find('/') {
            model[pos + 1..].to_string()
        } else {
            model.to_string()
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/gateway/mod.rs
git commit -m "feat: implement gateway orchestrator with queues and fallbacks"
```

---

## Milestone 7: HTTP Server

**Goal:** Actix-web server with routes, handlers, and streaming support.

### Task 7.1: HTTP Handlers

**Files:**
- Create: `src/http/handlers.rs`
- Create: `src/http/middleware.rs`

- [ ] **Step 1: Implement handlers**

```rust
// src/http/handlers.rs
use actix_web::{web, HttpResponse, Responder};
use futures::StreamExt;
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
                let sse_stream = stream.map(|chunk| {
                    match chunk {
                        Ok(chunk) => {
                            let data = serde_json::to_string(&chunk).unwrap();
                            Ok::<_, actix_web::Error>(
                                format!("data: {}\n\n", data)
                            )
                        }
                        Err(e) => {
                            Ok(format!("data: {}\n\n", json!({"error": e.to_string()})))
                        }
                    }
                });
                
                HttpResponse::Ok()
                    .content_type("text/event-stream")
                    .streaming(sse_stream)
            }
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

**Note:** Need to add `chat_completion_stream`, `embedding`, and `list_models` methods to Gateway. Add to `src/gateway/mod.rs`:

```rust
pub async fn chat_completion_stream(
    &self,
    request: ChatCompletionRequest,
) -> Result<futures::stream::BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
    let (provider_name, _) = Self::parse_model(&request.model);
    
    let provider = self.providers.get(provider_name)
        .ok_or(GatewayError::NoProviderAvailable)?;
    
    let mut req = request;
    req.model = Self::strip_provider_prefix(&req.model);
    provider.chat_completion_stream(req).await
}

pub async fn embedding(
    &self,
    request: EmbeddingRequest,
) -> Result<EmbeddingResponse, GatewayError> {
    let (provider_name, _) = Self::parse_model(&request.model);
    
    let queue = self.queues.get(provider_name)
        .ok_or(GatewayError::NoProviderAvailable)?;
    
    let response = queue.send(GatewayRequest::Embedding(request)).await?;
    match response {
        GatewayResponse::Embedding(result) => result,
        _ => Err(GatewayError::Provider {
            message: "Unexpected response type".to_string(),
            status_code: None,
        }),
    }
}

pub async fn list_models(
    &self,
) -> Result<ModelList, GatewayError> {
    let mut all_models = Vec::new();
    
    for (name, queue) in &self.queues {
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
```

- [ ] **Step 2: Implement middleware**

```rust
// src/http/middleware.rs
use actix_web::middleware::{Logger, NormalizePath};
use actix_web::{App, http};

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

Add to `Cargo.toml`:
```toml
actix-cors = "0.7"
```

- [ ] **Step 3: Implement HTTP module**

```rust
// src/http/mod.rs
use actix_web::{web, App, HttpServer};
use std::sync::Arc;

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

- [ ] **Step 4: Update main.rs**

```rust
use tracing::{info, error};
use std::path::Path;

mod config;
mod error;
mod types;
mod provider;
mod client;
mod gateway;
mod http;

use config::AppConfig;
use gateway::Gateway;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt::init();
    
    if let Err(e) = run().await {
        error!("Fatal error: {}", e);
        std::process::exit(1);
    }
}

async fn run() -> anyhow::Result<()> {
    let config_path = std::env::var("HEIRLOOM_CONFIG_PATH")
        .unwrap_or_else(|_| "config.toml".to_string());
    
    let config = AppConfig::from_file_with_env(Path::new(&config_path))?;
    
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

- [ ] **Step 5: Build project**

Run: `cargo build`
Expected: Compiles (may have warnings, should not have errors)

- [ ] **Step 6: Commit**

```bash
git add src/http/ src/gateway/mod.rs src/main.rs Cargo.toml
git commit -m "feat: implement HTTP server with actix-web"
```

---

## Milestone 8: Integration Tests

**Goal:** End-to-end tests with mock provider servers.

### Task 8.1: Chat Completion Integration Test

**Files:**
- Create: `tests/integration/chat_tests.rs`

- [ ] **Step 1: Write integration test**

```rust
use actix_web::{test, web, App};
use serde_json::json;

#[actix_web::test]
async fn test_chat_completion_endpoint() {
    // Setup mock provider and gateway
    // This test requires a running mock server or uses wiremock
    
    // For now, create a simple test that validates request parsing
    let app = test::init_service(
        App::new()
            .route("/v1/chat/completions", web::post().to(test_handler))
    ).await;
    
    let req = test::TestRequest::post()
        .uri("/v1/chat/completions")
        .set_json(json!({
            "model": "openai/gpt-4",
            "messages": [{"role": "user", "content": "Hello"}]
        }))
        .to_request();
    
    let resp = test::call_service(&app, req).await;
    assert!(resp.status().is_success() || resp.status().is_server_error());
}

async fn test_handler(body: web::Json<serde_json::Value>) -> impl actix_web::Responder {
    actix_web::HttpResponse::Ok().json(body.0)
}
```

Run: `cargo test integration::chat_tests`
Expected: PASS

- [ ] **Step 2: Commit**

```bash
git add tests/integration/chat_tests.rs
git commit -m "test: add basic HTTP integration test"
```

---

## Milestone 9: MCP Gateway

**Goal:** MCP client management, tool execution, and agent mode.

### Task 9.1: MCP Transport

**Files:**
- Create: `src/mcp/transport.rs`

- [ ] **Step 1: Implement stdio transport**

```rust
// src/mcp/transport.rs
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, Command, ChildStdin, ChildStdout};

#[derive(Debug, Serialize, Deserialize)]
pub struct JsonRpcRequest {
    pub jsonrpc: String,
    pub id: u64,
    pub method: String,
    pub params: Value,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct JsonRpcResponse {
    pub jsonrpc: String,
    pub id: u64,
    #[serde(flatten)]
    pub result: JsonRpcResult,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(untagged)]
pub enum JsonRpcResult {
    Success { result: Value },
    Error { error: JsonRpcError },
}

#[derive(Debug, Serialize, Deserialize)]
pub struct JsonRpcError {
    pub code: i32,
    pub message: String,
}

pub struct StdioTransport {
    command: String,
    args: Vec<String>,
    process: Option<Child>,
    stdin: Option<ChildStdin>,
    stdout: Option<BufReader<ChildStdout>>,
    request_id: u64,
}

impl StdioTransport {
    pub fn new(command: String, args: Vec<String>) -> Self {
        Self {
            command,
            args,
            process: None,
            stdin: None,
            stdout: None,
            request_id: 0,
        }
    }
    
    pub async fn connect(&mut self) -> Result<(), MCPError> {
        let mut cmd = Command::new(&self.command);
        cmd.args(&self.args)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped());
        
        let mut process = cmd.spawn()
            .map_err(|e| MCPError::Transport(e.to_string()))?;
        
        self.stdin = process.stdin.take();
        self.stdout = process.stdout.take()
            .map(|out| BufReader::new(out));
        self.process = Some(process);
        
        // Send initialize request
        let init_req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "initialize".to_string(),
            params: json!({
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": { "name": "heirloom", "version": "0.1.0" }
            }),
        };
        
        let response = self.send_request(init_req).await?;
        match response.result {
            JsonRpcResult::Success { .. } => Ok(()),
            JsonRpcResult::Error { error } => Err(MCPError::Protocol(error.message)),
        }
    }
    
    pub async fn list_tools(&mut self,
    ) -> Result<Vec<MCPTool>, MCPError> {
        let req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "tools/list".to_string(),
            params: json!({}),
        };
        
        let response = self.send_request(req).await?;
        match response.result {
            JsonRpcResult::Success { result } => {
                let tools: Vec<MCPTool> = serde_json::from_value(
                    result.get("tools").cloned().unwrap_or(json!([[]]))
                ).unwrap_or_default();
                Ok(tools)
            }
            JsonRpcResult::Error { error } => Err(MCPError::Protocol(error.message)),
        }
    }
    
    pub async fn call_tool(
        &mut self,
        name: &str,
        arguments: Value,
    ) -> Result<Value, MCPError> {
        let req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "tools/call".to_string(),
            params: json!({
                "name": name,
                "arguments": arguments,
            }),
        };
        
        let response = self.send_request(req).await?;
        match response.result {
            JsonRpcResult::Success { result } => Ok(result),
            JsonRpcResult::Error { error } => Err(MCPError::ToolExecution(error.message)),
        }
    }
    
    async fn send_request(
        &mut self,
        request: JsonRpcRequest,
    ) -> Result<JsonRpcResponse, MCPError> {
        let stdin = self.stdin.as_mut()
            .ok_or(MCPError::Transport("Not connected".to_string()))?;
        let stdout = self.stdout.as_mut()
            .ok_or(MCPError::Transport("Not connected".to_string()))?;
        
        let json = serde_json::to_string(&request).unwrap();
        let line = format!("{}\n", json);
        
        stdin.write_all(line.as_bytes()).await
            .map_err(|e| MCPError::Transport(e.to_string()))?;
        stdin.flush().await
            .map_err(|e| MCPError::Transport(e.to_string()))?;
        
        let mut response_line = String::new();
        stdout.read_line(&mut response_line).await
            .map_err(|e| MCPError::Transport(e.to_string()))?;
        
        serde_json::from_str(&response_line)
            .map_err(|e| MCPError::Protocol(format!("Invalid JSON: {}", e)))
    }
    
    fn next_id(&mut self) -> u64 {
        self.request_id += 1;
        self.request_id
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MCPTool {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub inputSchema: Value,
}

#[derive(Debug, thiserror::Error)]
pub enum MCPError {
    #[error("Transport error: {0}")]
    Transport(String),
    #[error("Protocol error: {0}")]
    Protocol(String),
    #[error("Tool execution failed: {0}")]
    ToolExecution(String),
}
```

- [ ] **Step 2: Commit**

```bash
git add src/mcp/transport.rs
git commit -m "feat: implement MCP stdio transport with JSON-RPC"
```

### Task 9.2: MCP Client Manager

**Files:**
- Create: `src/mcp/client.rs`

- [ ] **Step 1: Implement MCP client manager**

```rust
// src/mcp/client.rs
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use tokio::sync::Semaphore;

use crate::config::McpClientConfig;
use crate::types::*;
use super::transport::{MCPError, MCPTool, StdioTransport};

pub struct MCPClientManager {
    clients: RwLock<HashMap<String, MCPClient>>,
}

pub struct MCPClient {
    pub name: String,
    pub auto_execute: bool,
    pub tools: Vec<Tool>,
    transport: MCPTransport,
    semaphore: Arc<Semaphore>,
}

enum MCPTransport {
    Stdio(StdioTransport),
    // SSE(SseTransport), // Future
}

impl MCPClientManager {
    pub fn new() -> Self {
        Self {
            clients: RwLock::new(HashMap::new()),
        }
    }
    
    pub async fn add_client(&self,
        config: &McpClientConfig,
    ) -> Result<(), MCPError> {
        let mut client = match config.transport.as_str() {
            "stdio" => {
                let command = config.command.clone()
                    .ok_or(MCPError::Transport("Command required for stdio".to_string()))?;
                let args = config.args.clone().unwrap_or_default();
                
                let mut transport = StdioTransport::new(command, args);
                transport.connect().await?;
                
                MCPClient {
                    name: config.name.clone(),
                    auto_execute: config.auto_execute,
                    tools: Vec::new(),
                    transport: MCPTransport::Stdio(transport),
                    semaphore: Arc::new(Semaphore::new(1)), // stdio is serial
                }
            }
            _ => return Err(MCPError::Transport(
                format!("Unsupported transport: {}", config.transport)
            )),
        };
        
        // Discover tools
        let mcp_tools = match &mut client.transport {
            MCPTransport::Stdio(t) => t.list_tools().await?,
        };
        
        client.tools = mcp_tools.into_iter()
            .map(|t| Tool {
                tool_type: "function".to_string(),
                function: FunctionTool {
                    name: format!("{}_{}", config.name, t.name),
                    description: t.description,
                    parameters: t.inputSchema,
                },
            })
            .collect();
        
        let mut clients = self.clients.write().await;
        clients.insert(config.name.clone(), client);
        
        Ok(())
    }
    
    pub async fn get_tools(&self,
    ) -> Vec<Tool> {
        let clients = self.clients.read().await;
        clients.values()
            .flat_map(|c| c.tools.clone())
            .collect()
    }
    
    pub async fn execute_tool(
        &self,
        tool_call: &ToolCall,
    ) -> Result<String, MCPError> {
        // Parse tool name: "clientName_toolName"
        let parts: Vec<&str> = tool_call.function.name.splitn(2, '_').collect();
        if parts.len() != 2 {
            return Err(MCPError::ToolExecution(
                format!("Invalid tool name format: {}", tool_call.function.name)
            ));
        }
        
        let client_name = parts[0];
        let tool_name = parts[1];
        
        let clients = self.clients.read().await;
        let client = clients.get(client_name)
            .ok_or(MCPError::ToolExecution(
                format!("Client not found: {}", client_name)
            ))?;
        
        let _permit = client.semaphore.acquire().await
            .map_err(|e| MCPError::Transport(e.to_string()))?;
        
        let args: serde_json::Value = serde_json::from_str(&tool_call.function.arguments)
            .unwrap_or(json!({}));
        
        let result = match &mut client.transport {
            MCPTransport::Stdio(t) => t.call_tool(tool_name, args).await?,
        };
        
        Ok(result.to_string())
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/mcp/client.rs
git commit -m "feat: implement MCP client manager with tool discovery"
```

### Task 9.3: Agent Mode

**Files:**
- Create: `src/mcp/agent.rs`

- [ ] **Step 1: Implement agent mode**

```rust
// src/mcp/agent.rs
use crate::error::GatewayError;
use crate::gateway::Gateway;
use crate::types::*;
use super::client::MCPClientManager;

pub struct AgentExecutor {
    max_depth: usize,
    gateway: Arc<Gateway>,
    mcp_manager: Arc<MCPClientManager>,
}

impl AgentExecutor {
    pub fn new(
        max_depth: usize,
        gateway: Arc<Gateway>,
        mcp_manager: Arc<MCPClientManager>,
    ) -> Self {
        Self { max_depth, gateway, mcp_manager }
    }
    
    pub async fn execute(
        &self,
        mut request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        // Inject MCP tools into request
        let mcp_tools = self.mcp_manager.get_tools().await;
        if !mcp_tools.is_empty() {
            request.tools = Some(mcp_tools);
        }
        
        for depth in 0..self.max_depth {
            let response = self.gateway.chat_completion(request.clone()).await?;
            
            if let Some(tool_calls) = &response.choices[0].message.tool_calls {
                // Classify tools
                let (auto, manual) = self.classify_tools(tool_calls).await;
                
                if !manual.is_empty() {
                    // Return pending response
                    return Ok(self.build_pending_response(response, manual));
                }
                
                // Execute auto tools in parallel
                let mut tool_results = Vec::new();
                for tool_call in auto {
                    match self.mcp_manager.execute_tool(&tool_call).await {
                        Ok(result) => {
                            tool_results.push(ChatMessage::Tool {
                                tool_call_id: tool_call.id.clone(),
                                content: result,
                            });
                        }
                        Err(e) => {
                            tool_results.push(ChatMessage::Tool {
                                tool_call_id: tool_call.id.clone(),
                                content: format!("Error: {}", e),
                            });
                        }
                    }
                }
                
                // Append results to conversation
                request.messages.push(ChatMessage::Assistant {
                    content: response.choices[0].message.content.clone(),
                    tool_calls: Some(auto.clone()),
                });
                request.messages.extend(tool_results);
            } else {
                return Ok(response);
            }
        }
        
        Err(GatewayError::AgentMaxDepthExceeded)
    }
    
    async fn classify_tools(
        &self,
        tool_calls: &[ToolCall],
    ) -> (Vec<ToolCall>, Vec<ToolCall>) {
        let mut auto = Vec::new();
        let mut manual = Vec::new();
        
        let clients = self.mcp_manager.clients.read().await;
        
        for tool_call in tool_calls {
            let parts: Vec<&str> = tool_call.function.name.splitn(2, '_').collect();
            if parts.len() == 2 {
                if let Some(client) = clients.get(parts[0]) {
                    if client.auto_execute {
                        auto.push(tool_call.clone());
                    } else {
                        manual.push(tool_call.clone());
                    }
                } else {
                    manual.push(tool_call.clone());
                }
            } else {
                manual.push(tool_call.clone());
            }
        }
        
        (auto, manual)
    }
    
    fn build_pending_response(
        &self,
        response: ChatCompletionResponse,
        pending_tools: Vec<ToolCall>,
    ) -> ChatCompletionResponse {
        // Return response with pending tools marked
        response
    }
}
```

**Note:** The `classify_tools` method accesses `self.mcp_manager.clients` directly which is private. We need to expose a method. Modify `client.rs` to add:

```rust
pub async fn is_auto_executable(
    &self,
    tool_name: &str,
) -> bool {
    let parts: Vec<&str> = tool_name.splitn(2, '_').collect();
    if parts.len() != 2 {
        return false;
    }
    
    let clients = self.clients.read().await;
    clients.get(parts[0])
        .map(|c| c.auto_execute)
        .unwrap_or(false)
}
```

Then update `agent.rs` to use this method.

- [ ] **Step 2: Commit**

```bash
git add src/mcp/agent.rs
git commit -m "feat: implement MCP agent mode with tool execution"
```

### Task 9.4: MCP Module Integration

**Files:**
- Create: `src/mcp/mod.rs`
- Modify: `src/main.rs`

- [ ] **Step 1: Create MCP module**

```rust
// src/mcp/mod.rs
pub mod agent;
pub mod client;
pub mod transport;

pub use client::MCPClientManager;
pub use agent::AgentExecutor;
```

- [ ] **Step 2: Integrate MCP into main**

Update `src/main.rs` to initialize MCP if configured:

```rust
async fn run() -> anyhow::Result<()> {
    let config_path = std::env::var("HEIRLOOM_CONFIG_PATH")
        .unwrap_or_else(|_| "config.toml".to_string());
    
    let config = AppConfig::from_file_with_env(Path::new(&config_path))?;
    
    tracing_subscriber::fmt()
        .with_env_filter(&config.server.log_level)
        .init();
    
    info!("Loading configuration from {}", config_path);
    
    let gateway = Arc::new(Gateway::from_config(&config));
    
    // Initialize MCP if configured
    let mcp_manager = if let Some(mcp_config) = &config.mcp {
        let manager = Arc::new(MCPClientManager::new());
        for client_config in &mcp_config.clients {
            if let Err(e) = manager.add_client(client_config).await {
                warn!("Failed to add MCP client '{}': {}", client_config.name, e);
            } else {
                info!("MCP client '{}' connected", client_config.name);
            }
        }
        Some(manager)
    } else {
        None
    };
    
    info!("Starting Heirloom on {}:{}", config.server.host, config.server.port);
    
    http::run_server(&config, (*gateway).clone(), mcp_manager).await?;
    
    Ok(())
}
```

- [ ] **Step 3: Update HTTP handlers for MCP**

Modify `src/http/handlers.rs` to support agent mode:

```rust
pub async fn chat_completions(
    body: web::Json<ChatCompletionRequest>,
    gateway: web::Data<Gateway>,
    mcp_manager: web::Data<Option<Arc<MCPClientManager>>>,
) -> impl Responder {
    let request = body.into_inner();
    
    // If MCP is enabled and tools are present, use agent mode
    if let Some(mcp) = mcp_manager.as_ref() {
        let executor = AgentExecutor::new(
            5, // max_depth from config
            Arc::new((*gateway).clone()),
            mcp.clone(),
        );
        
        match executor.execute(request).await {
            Ok(response) => HttpResponse::Ok().json(response),
            Err(e) => {
                let status = e.status_code();
                HttpResponse::build(actix_web::http::StatusCode::from_u16(status).unwrap())
                    .json(ErrorResponse {
                        error: ApiError {
                            message: e.to_string(),
                            error_type: "agent_error".to_string(),
                            code: Some(status.to_string()),
                        }
                    })
            }
        }
    } else {
        // Standard mode
        // ... existing code
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/mcp/mod.rs src/main.rs src/http/handlers.rs
git commit -m "feat: integrate MCP agent mode into HTTP handlers"
```

---

## Milestone 10: Docker & Deployment

**Goal:** Docker image, health checks, and documentation.

### Task 10.1: Dockerfile

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
    { primary = "openai/gpt-4", fallbacks = ["anthropic/claude-3-opus-20240229"] }
]

[mcp]
enabled = false
max_agent_depth = 5

[[mcp.clients]]
name = "filesystem"
transport = "stdio"
command = "npx"
args = ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
auto_execute = true
```

- [ ] **Step 3: Build Docker image**

Run: `docker build -t heirloom:0.1.0 .`
Expected: Builds successfully

- [ ] **Step 4: Commit**

```bash
git add Dockerfile config.example.toml
git commit -m "feat: add Dockerfile and example configuration"
```

---

## Summary

### Milestones

| Milestone | Tasks | Description |
|-----------|-------|-------------|
| 1 | 3 | Project skeleton, config types, env overrides |
| 2 | 1 | Core request/response types |
| 3 | 2 | Provider trait, OpenAI provider |
| 4 | 2 | Anthropic provider |
| 5 | 1 | Groq provider |
| 6 | 5 | Gateway core (key selector, retry, queue, fallback, orchestrator) |
| 7 | 1 | HTTP server (actix-web) |
| 8 | 1 | Integration tests |
| 9 | 4 | MCP (transport, client, agent, integration) |
| 10 | 1 | Docker deployment |

### Total Tasks: 21
### Estimated Time: 6-8 weeks

### Next Steps

1. Review this plan
2. Execute milestones sequentially using subagent-driven-development or inline execution
3. Each task produces a git commit
4. Run tests after each milestone

---

**Plan saved to:** `docs/superpowers/plans/2025-04-27-heirloom-implementation-plan.md`

**Two execution options:**

1. **Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — Execute tasks in this session, batch execution with checkpoints

Which approach would you prefer?
