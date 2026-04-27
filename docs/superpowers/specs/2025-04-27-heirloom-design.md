# Heirloom Design Specification

> **Date:** 2025-04-27  
> **Status:** Draft  
> **Goal:** A lightweight Rust AI Gateway — OpenAI-compatible API with multi-provider support, fallback, retries, and MCP tool integration.

---

## 1. Overview

Heirloom is a minimal, high-performance AI Gateway written in Rust. It provides a single OpenAI-compatible HTTP API that proxies requests to multiple LLM providers (OpenAI, Anthropic, Groq) with automatic failover, retries, and load balancing across API keys.

Compared to Bifrost (Go), Heirloom strips enterprise features (plugins, governance, semantic cache, UI, WebSocket) to focus on the core value: **a reliable, fast, drop-in replacement for direct provider calls**.

---

## 2. Goals & Non-Goals

### Goals
- OpenAI-compatible HTTP API (`/v1/chat/completions`, `/v1/embeddings`, `/v1/models`)
- Support 3 providers: OpenAI, Anthropic, Groq
- Chat Completion (streaming + non-streaming) + Embedding + List Models
- Automatic failover across fallback providers
- Retries with exponential backoff
- Weighted random API key selection
- Per-provider request queue isolation
- SSE streaming
- MCP Gateway: stdio + SSE clients, agent mode with tool auto-execution
- Structured logging (tracing)
- Cloud-native deployment (Docker, health checks)

### Non-Goals
- Plugin system (Pre/Post hooks)
- Governance (budget, rate limiting, virtual keys, RBAC)
- Semantic caching
- WebSocket / Realtime API
- Batch, File, Container, Video, Image, Speech APIs
- Web UI
- 20+ providers (only 3 core ones)
- Code Mode (Starlark sandbox)
- OAuth2 flows

---

## 3. Architecture

### 3.1 High-Level Flow

```
Client HTTP Request
  → Actix-web Router (CORS, logging middleware)
    → HTTP Handler
      → Request Validation & Parsing
        → Gateway Core
          ├─ Provider Queue (tokio::sync::mpsc)
          ├─ Key Selection (weighted random)
          ├─ Retry Logic (exponential backoff)
          └─ Fallback Chain
            → Provider Client (reqwest)
              → Provider API (OpenAI / Anthropic / Groq)
            ← Response
        ← Response Serialization (OpenAI format)
    ← HTTP Response
```

### 3.2 Module Structure

```
heirloom/
├── Cargo.toml              # Workspace root
├── src/
│   ├── main.rs             # Entry point: parse args, load config, start server
│   ├── config.rs           # Configuration structs and loading (TOML + env)
│   ├── error.rs            # Error types and conversions
│   ├── types/              # Core request/response types
│   │   ├── mod.rs
│   │   ├── chat.rs         # Chat completion types
│   │   ├── embedding.rs    # Embedding types
│   │   ├── models.rs       # Model list types
│   │   └── common.rs       # Shared types (Usage, Error, etc.)
│   ├── provider/           # Provider abstraction and implementations
│   │   ├── mod.rs          # Provider trait + registry
│   │   ├── openai.rs
│   │   ├── anthropic.rs
│   │   └── groq.rs
│   ├── gateway/            # Core gateway logic
│   │   ├── mod.rs          # Public API (process_chat, process_embedding)
│   │   ├── queue.rs        # Per-provider request queues
│   │   ├── fallback.rs     # Fallback chain logic
│   │   ├── retry.rs        # Retry with exponential backoff
│   │   └── key_selector.rs # Weighted random key selection
│   ├── http/               # HTTP server layer
│   │   ├── mod.rs          # Server initialization
│   │   ├── handlers.rs     # Actix route handlers
│   │   └── middleware.rs   # Logging, CORS, request ID
│   ├── mcp/                # MCP Gateway
│   │   ├── mod.rs
│   │   ├── client.rs       # MCP client management (stdio, SSE)
│   │   ├── transport.rs    # MCP transport implementations
│   │   ├── tools.rs        # Tool discovery and execution
│   │   └── agent.rs        # Agent mode loop
│   └── client/             # HTTP client wrapper
│       └── mod.rs          # reqwest client with connection pooling
└── Dockerfile
```

---

## 4. Core Components

### 4.1 Configuration (`config.rs`)

Loaded from TOML file + environment variable overrides.

```toml
[server]
host = "0.0.0.0"
port = 8080
log_level = "info"

[providers.openai]
enabled = true
base_url = "https://api.openai.com"
keys = [
    { value = "sk-xxx", weight = 1.0 },
    { value = "sk-yyy", weight = 2.0 }
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
keys = [{ value = "sk-ant-xxx", weight = 1.0 }]

[providers.groq]
enabled = true
base_url = "https://api.groq.com/openai/v1"
keys = [{ value = "gsk_xxx", weight = 1.0 }]

# Optional: fallback chain
[fallbacks]
# For requests to openai/gpt-4, fallback to anthropic/claude-3-opus, then groq/llama3-70b
chains = [
    { primary = "openai/gpt-4", fallbacks = ["anthropic/claude-3-opus-20240229", "groq/llama-3-70b-8192"] }
]

[mcp]
enabled = true
max_agent_depth = 5

[[mcp.clients]]
name = "filesystem"
transport = "stdio"
command = "npx"
args = ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
auto_execute = true

[[mcp.clients]]
name = "fetch"
transport = "sse"
url = "http://localhost:3001/sse"
auto_execute = true
```

### 4.2 Provider Trait (`provider/mod.rs`)

```rust
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
```

**Key design decisions:**
- Groq implements Provider directly (OpenAI-compatible, delegates to openai module)
- Each provider holds its own `reqwest::Client` with configured connection pool
- Streaming returns `BoxStream` for SSE chunk production

### 4.3 Gateway Core (`gateway/`)

#### Request Queue (`queue.rs`)

Each provider has a dedicated `tokio::sync::mpsc` queue with configurable concurrency:

```rust
pub struct ProviderQueue {
    tx: mpsc::Sender<QueuedRequest>,
    config: QueueConfig,
}

struct QueuedRequest {
    request: GatewayRequest,
    response_tx: oneshot::Sender<GatewayResponse>,
}
```

- Queue per provider prevents one provider's slowdown from affecting others
- Worker tasks (equal to `queue_concurrency`) pull from queue and execute
- Queue buffer size prevents unbounded memory growth

#### Fallback (`fallback.rs`)

```rust
pub struct FallbackChain {
    primary: ProviderRef,
    fallbacks: Vec<ProviderRef>,
}

impl FallbackChain {
    pub async fn execute<F, T>(&self, operation: F) -> Result<T, GatewayError>
    where
        F: Fn(&ProviderRef) -> Future<Output = Result<T, GatewayError>>,
    {
        // Try primary, then each fallback in order
        // Stop when one succeeds or all fail
    }
}
```

**Fallback triggers:**
- HTTP 5xx errors
- Network errors (DNS, timeout, connection refused)
- HTTP 429 (rate limit) — after retries exhausted

**Non-fallback errors (return immediately):**
- HTTP 4xx (client errors, except 429)
- Authentication errors

#### Retry (`retry.rs`)

```rust
pub struct RetryPolicy {
    max_retries: u32,
    backoff_initial: Duration,
    backoff_max: Duration,
}

impl RetryPolicy {
    pub async fn execute<F, T>(&self, operation: F) -> Result<T, GatewayError>
    where
        F: Future<Output = Result<T, GatewayError>>,
    {
        // Exponential backoff with jitter
        // Retry on: 5xx, 429, network errors
        // Don't retry: 4xx (except 429)
    }
}
```

**Backoff formula:** `min(backoff_initial * 2^attempt, backoff_max) + random_jitter`

#### Key Selection (`key_selector.rs`)

```rust
pub struct WeightedKey {
    pub value: String,
    pub weight: f64,
}

pub fn select_key(keys: &[WeightedKey]) -> &WeightedKey {
    // Weighted random selection
    // Total weight = sum of all weights
    // Random point in [0, total_weight)
    // Return key covering that point
}
```

### 4.4 HTTP Layer (`http/`)

#### Handlers (`handlers.rs`)

```rust
// POST /v1/chat/completions
async fn chat_completions(
    body: web::Json<ChatCompletionRequest>,
    gateway: web::Data<Gateway>,
) -> impl Responder {
    // Parse request
    // Resolve provider from model string ("provider/model" or "model")
    // Call gateway.process_chat(request)
    // If stream=true, return SSE stream
    // Else return JSON response
}

// POST /v1/embeddings
async fn embeddings(
    body: web::Json<EmbeddingRequest>,
    gateway: web::Data<Gateway>,
) -> impl Responder;

// GET /v1/models
async fn list_models(
    gateway: web::Data<Gateway>,
) -> impl Responder;

// GET /health
async fn health() -> impl Responder;
```

**Model string parsing:**
- `"gpt-4"` → provider from config default or mapping
- `"openai/gpt-4"` → explicit provider
- `"anthropic/claude-3-opus-20240229"` → explicit provider

#### Streaming

For SSE streaming:
- Use `actix_web::HttpResponse::Streaming`
- Stream items are `Bytes` (serialized SSE chunks)
- Format: `data: {...}\n\n`

### 4.5 MCP Gateway (`mcp/`)

#### Transport (`transport.rs`)

**stdio transport:**
```rust
pub struct StdioTransport {
    command: String,
    args: Vec<String>,
    process: Option<Child>,
    stdin: Option<ChildStdin>,
    stdout: Option<BufReader<ChildStdout>>,
}

impl MCPTransport for StdioTransport {
    async fn initialize(&mut self) -> Result<InitializeResult, MCPError>;
    async fn list_tools(&mut self) -> Result<ListToolsResult, MCPError>;
    async fn call_tool(&mut self, name: &str, arguments: Value) -> Result<CallToolResult, MCPError>;
    async fn close(&mut self);
}
```

**SSE transport:**
```rust
pub struct SseTransport {
    url: String,
    headers: HashMap<String, String>,
    client: reqwest::Client,
    session_id: Option<String>,
}

// Uses SSE endpoint for server→client, HTTP POST for client→server
```

#### Client Manager (`client.rs`)

```rust
pub struct MCPClientManager {
    clients: RwLock<HashMap<String, MCPClient>>,
}

pub struct MCPClient {
    name: String,
    transport: Box<dyn MCPTransport>,
    tools: Vec<Tool>,
    auto_execute: bool,
    semaphore: Arc<Semaphore>, // Concurrency control
}
```

**Concurrency control:**
- stdio: `Semaphore(1)` — single subprocess, serial execution
- SSE: `Semaphore(n)` — configurable, default 5

#### Agent Mode (`agent.rs`)

```rust
pub struct AgentExecutor {
    max_depth: usize,
    gateway: Arc<Gateway>,
    mcp_manager: Arc<MCPClientManager>,
}

impl AgentExecutor {
    pub async fn execute(
        &self,
        mut request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        for depth in 0..self.max_depth {
            // 1. Call LLM
            let response = self.gateway.chat_completion(request.clone()).await?;
            
            // 2. Check for tool_calls
            if let Some(tool_calls) = &response.tool_calls {
                // 3. Classify: auto-executable vs manual
                let (auto, manual) = self.classify_tools(tool_calls);
                
                if !manual.is_empty() {
                    // Return partial result with pending tools
                    return Ok(self.build_pending_response(response, manual));
                }
                
                // 4. Execute auto tools in parallel
                let results = self.execute_tools_parallel(auto).await;
                
                // 5. Append results to conversation
                request.messages.extend(results);
            } else {
                // No tool calls, return final response
                return Ok(response);
            }
        }
        
        Err(GatewayError::AgentMaxDepthExceeded)
    }
}
```

**Tool classification:**
- `auto_execute = true` client → all tools auto-executable
- `auto_execute = false` or unset → all tools require manual confirmation
- No per-tool granularity (simplified from Bifrost)

---

## 5. Data Types

### 5.1 Chat Completion

```rust
// Request (OpenAI-compatible)
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
    pub tools: Option<Vec<Tool>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_choice: Option<ToolChoice>,
    // ... other standard OpenAI fields
}

pub enum ChatMessage {
    System { content: String },
    User { content: String },
    Assistant { content: Option<String>, tool_calls: Option<Vec<ToolCall>> },
    Tool { tool_call_id: String, content: String },
}

// Response
pub struct ChatCompletionResponse {
    pub id: String,
    pub object: String,
    pub created: u64,
    pub model: String,
    pub choices: Vec<Choice>,
    pub usage: Usage,
}

// Streaming chunk
pub struct ChatCompletionChunk {
    pub id: String,
    pub object: String,
    pub created: u64,
    pub model: String,
    pub choices: Vec<ChunkChoice>,
}
```

### 5.2 Embedding

```rust
pub struct EmbeddingRequest {
    pub model: String,
    pub input: EmbeddingInput,
}

pub enum EmbeddingInput {
    Single(String),
    Multiple(Vec<String>),
}

pub struct EmbeddingResponse {
    pub object: String,
    pub data: Vec<EmbeddingData>,
    pub model: String,
    pub usage: Usage,
}
```

### 5.3 Error Response

```rust
pub struct ErrorResponse {
    pub error: ApiError,
}

pub struct ApiError {
    pub message: String,
    #[serde(rename = "type")]
    pub error_type: String,
    pub code: Option<String>,
}
```

---

## 6. Provider Implementations

### 6.1 OpenAI

- **Base URL:** `https://api.openai.com`
- **Endpoints:**
  - POST `/v1/chat/completions`
  - POST `/v1/embeddings`
  - GET `/v1/models`
- **Auth:** `Authorization: Bearer {key}`
- **Streaming:** SSE with `data: {...}` format

### 6.2 Anthropic

- **Base URL:** `https://api.anthropic.com`
- **Endpoints:**
  - POST `/v1/messages` (chat)
  - POST `/v1/embeddings`
  - GET `/v1/models`
- **Auth:** `x-api-key: {key}`, `anthropic-version: 2023-06-01`
- **Request format:** Anthropic Messages API
- **Conversion:** Heirloom converts OpenAI format ↔ Anthropic format internally

### 6.3 Groq

- **Base URL:** `https://api.groq.com/openai/v1`
- **Implementation:** Delegates to OpenAI provider (fully compatible)
- **Only differences:** base URL and key format

---

## 7. Error Handling

### Retryable Errors (with retry)
- HTTP 429 (rate limit)
- HTTP 5xx
- Network errors (timeout, DNS, connection refused)
- HTTP 408 (request timeout)

### Non-Retryable Errors (immediate fail)
- HTTP 400 (bad request)
- HTTP 401 (unauthorized)
- HTTP 403 (forbidden)
- HTTP 404 (not found)
- HTTP 422 (validation error)

### Fallback Triggers
After retries exhausted:
- All retryable errors → try fallback provider
- Non-retryable errors → return immediately (don't fallback)

---

## 8. Observability

### Logging (tracing)
- Request/response logging (at debug level)
- Provider call latency
- Fallback events
- Retry attempts
- Queue depth (periodic)

### Metrics (optional future)
- Request count by provider
- Error rate by provider
- Latency histograms
- Queue depth gauge

### Health Check
- `GET /health` → `{"status": "ok"}`
- Checks: server running, at least one provider configured

---

## 9. Deployment

### Docker

```dockerfile
FROM rust:1.75-slim as builder
WORKDIR /app
COPY . .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/heirloom /usr/local/bin/heirloom
EXPOSE 8080
ENTRYPOINT ["heirloom"]
CMD ["--config", "/etc/heirloom/config.toml"]
```

### Environment Variables

- `HEIRLOOM_CONFIG_PATH` — config file path
- `HEIRLOOM_LOG_LEVEL` — override log level
- `HEIRLOOM_PORT` — override server port

---

## 10. Testing Strategy

### Unit Tests
- Key selection (weighted random distribution)
- Retry policy (backoff calculation, retry triggers)
- Model string parsing
- Request/response serialization

### Integration Tests
- Mock provider server (using `wiremock` or `mockito`)
- End-to-end chat completion (non-streaming)
- End-to-end chat completion (streaming)
- Fallback chain
- Retry behavior
- MCP tool execution

### Test Structure

```
tests/
├── unit/
│   ├── key_selector_tests.rs
│   ├── retry_tests.rs
│   └── fallback_tests.rs
├── integration/
│   ├── chat_tests.rs
│   ├── embedding_tests.rs
│   ├── fallback_tests.rs
│   └── mcp_tests.rs
└── fixtures/
    └── config.toml
```

---

## 11. Security Considerations

- API keys stored in config file or env vars (never logged)
- HTTPS only for provider connections
- No persistent storage of requests/responses (unless explicitly configured)
- Input validation on all API parameters
- Timeout on all outbound requests

---

## 12. Future Extensions (Post-MVP)

- Additional providers (Gemini, Azure, Cohere)
- Response caching (simple in-memory)
- Request/response logging to file/DB
- Prometheus metrics endpoint
- Config hot-reload
- More MCP transports (WebSocket)
- Tool result caching

---

## 13. Dependencies

```toml
[dependencies]
actix-web = "4"
tokio = { version = "1", features = ["full"] }
reqwest = { version = "0.12", features = ["json", "stream"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
toml = "0.8"
config = "0.14"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
thiserror = "1"
anyhow = "1"
futures = "0.3"
tokio-stream = "0.1"
bytes = "1"
uuid = { version = "1", features = ["v4"] }
chrono = "0.4"
rand = "0.8"
async-trait = "0.1"
```

---

## 14. Comparison: Bifrost vs Heirloom

| Feature | Bifrost | Heirloom |
|---------|---------|----------|
| **Providers** | 24 | 3 (OpenAI, Anthropic, Groq) |
| **APIs** | 30+ methods | 3 (chat, embedding, models) |
| **Plugin System** | Full (LLM/MCP/HTTP/Observability) | None |
| **MCP** | Full (client, server, agent, code mode) | Client + Agent |
| **Governance** | Budget, rate limit, virtual keys | None |
| **Cache** | Semantic cache | None |
| **UI** | React + Vite | None |
| **Transport** | fasthttp | actix-web |
| **Object Pool** | sync.Pool everywhere | None (Rust) |
| **Deployment** | Binary, Docker, K8s | Docker (cloud-native) |
| **Codebase** | ~50K lines Go | ~5K lines Rust (target) |

---

**Next Step:** Write implementation plan (plan.md) based on this spec.
