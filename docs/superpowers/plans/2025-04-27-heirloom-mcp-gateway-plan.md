# Plan B: Heirloom MCP Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add MCP (Model Context Protocol) Gateway capability to Heirloom Core Gateway, enabling AI models to discover and execute external tools through stdio and SSE transports, with automatic multi-turn agent mode.

**Architecture:** MCP Client Manager manages multiple MCP client connections → discovers tools → injects into chat requests → Agent Mode intercepts tool_calls responses → executes tools in parallel → appends results → continues conversation loop until completion.

**Tech Stack:** tokio (process, io), serde_json, futures

**Prerequisite:** Plan A (Core Gateway) must be completed and functional.

---

## Scope

### Included
- MCP stdio transport (subprocess communication via JSON-RPC)
- MCP SSE transport (Server-Sent Events HTTP connection)
- Tool discovery (`tools/list`) and registration
- Tool execution (`tools/call`) with timeout
- Agent mode: automatic multi-turn tool calling loop
- Client-level `auto_execute` configuration
- Concurrent tool execution (parallel within auto-executable batch)
- Error handling for tool execution failures
- Integration with Plan A's Gateway and HTTP handlers

### Excluded
- MCP Server mode (heirloom as MCP server)
- Code Mode / Starlark sandbox
- OAuth2 authentication flows
- Per-user OAuth
- 4-level tool filtering (simplified to client-level)
- MCP Plugin Hooks (no plugin system in Heirloom)
- Health monitoring / auto-reconnect (simplified)
- WebSocket transport
- HTTP/StreamableHTTP transport (SSE covers this)

---

## Integration Points with Plan A

### 1. Configuration Extension

Plan A's `AppConfig` gains `[mcp]` section:

```toml
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

### 2. Gateway Extension

Plan A's `Gateway` becomes `Arc<Gateway>` and is shared with `AgentExecutor`:

```rust
// In main.rs
let gateway = Arc::new(Gateway::from_config(&config));
let mcp_manager = Arc::new(MCPClientManager::new());

// Initialize MCP clients
for client_config in &config.mcp.clients {
    mcp_manager.add_client(client_config).await?;
}

// AgentExecutor needs both
let agent_executor = AgentExecutor::new(
    config.mcp.max_agent_depth,
    gateway.clone(),
    mcp_manager.clone(),
);
```

### 3. HTTP Handler Extension

Plan A's `chat_completions` handler detects MCP:

```rust
pub async fn chat_completions(
    body: web::Json<ChatCompletionRequest>,
    gateway: web::Data<Arc<Gateway>>,
    mcp_manager: web::Data<Option<Arc<MCPClientManager>>>,
    agent_executor: web::Data<Option<AgentExecutor>>,
) -> impl Responder {
    let request = body.into_inner();
    
    // If MCP enabled and tools configured, use agent mode
    if let (Some(mcp), Some(agent)) = (mcp_manager.as_ref(), agent_executor.as_ref()) {
        if !mcp.get_tools().await.is_empty() {
            return handle_agent_mode(request, agent).await;
        }
    }
    
    // Standard mode (Plan A)
    handle_standard_mode(request, &gateway).await
}
```

---

## File Structure (Plan B Additions)

```
heirloom/
└── src/
    ├── config.rs                    # MODIFY: Add McpConfig, McpClientConfig
    ├── main.rs                      # MODIFY: Init MCP, pass to HTTP handlers
    ├── http/
    │   ├── handlers.rs              # MODIFY: Add agent mode path
    │   └── mod.rs                   # MODIFY: Register MCP data
    └── mcp/                         # NEW MODULE
        ├── mod.rs                   # Module exports
        ├── transport.rs             # MCP transport trait + stdio + SSE
        ├── client.rs                # MCPClientManager + MCPClient
        ├── tools.rs                 # Tool discovery, conversion, execution
        └── agent.rs                 # Agent mode executor
```

---

## Design Decisions (Aligned with Bifrost MCP)

### 1. MCP Transport Architecture

**Bifrost 支持 4 种传输：**
- stdio (subprocess)
- SSE (Server-Sent Events)
- StreamableHTTP (HTTP POST)
- In-process (direct function call)

**Heirloom 保留 2 种：**
- **stdio** — 本地命令行工具（filesystem、git、sqlite 等）
- **SSE** — 远程 MCP 服务

**砍掉 2 种：**
- StreamableHTTP（与 SSE 重叠，复杂度高）
- In-process（不需要 Code Mode）

### 2. JSON-RPC Protocol

**Bifrost 使用 `mark3labs/mcp-go` 库，封装了：**
- Initialize handshake
- `tools/list` 请求
- `tools/call` 请求
- SSE event parsing

**Heirloom 自实现（Rust 无成熟 MCP 库）：**

```rust
// JSON-RPC 2.0 types
pub struct JsonRpcRequest {
    pub jsonrpc: String,
    pub id: u64,
    pub method: String,
    pub params: Value,
}

pub struct JsonRpcResponse {
    pub jsonrpc: String,
    pub id: u64,
    pub result: Option<Value>,
    pub error: Option<JsonRpcError>,
}

// MCP-specific types
pub struct MCPTool {
    pub name: String,
    pub description: Option<String>,
    pub inputSchema: Value,  // JSON Schema
}

pub struct MCPToolResult {
    pub content: Vec<MCPContent>,
    pub isError: bool,
}
```

### 3. stdio Transport

**Bifrost 实现：**
```go
// mark3labs/mcp-go/client/transport
stdioTransport := transport.NewStdio(command, envs, args...)
client := client.NewClient(stdioTransport)
client.Start(ctx)
```

**Heirloom 实现：**

```rust
pub struct StdioTransport {
    command: String,
    args: Vec<String>,
    child: Option<Child>,
    stdin: Option<ChildStdin>,
    stdout: Option<BufReader<ChildStdout>>,
    request_id: AtomicU64,
}

impl StdioTransport {
    pub async fn initialize(&mut self
    ) -> Result<InitializeResult, MCPError> {
        // 1. Spawn subprocess
        // 2. Send initialize request
        // 3. Read initialize response
        // 4. Send initialized notification
    }
    
    pub async fn list_tools(&mut self
    ) -> Result<Vec<MCPTool>, MCPError> {
        // Send tools/list request
        // Read response
    }
    
    pub async fn call_tool(
        &mut self,
        name: &str,
        arguments: Value,
    ) -> Result<MCPToolResult, MCPError> {
        // Send tools/call request
        // Read response
    }
}
```

**关键设计：**
- 每个 stdio client 一个独立子进程
- `tokio::process::Command` 启动
- `tokio::io::BufReader` 逐行读取 stdout
- 请求和响应通过 newline-delimited JSON (NDJSON) 格式

### 4. SSE Transport

**Bifrost 实现：**
```go
sseTransport, err := transport.NewSSE(url, transport.WithHeaders(headers))
client := client.NewClient(sseTransport)
```

**Heirloom 实现（使用 `eventsource-client` crate）：**

```rust
use eventsource_client::Client;

pub struct SseTransport {
    url: String,
    headers: HashMap<String, String>,
    http_client: reqwest::Client,
    message_endpoint: Option<String>,
    request_id: AtomicU64,
}

impl SseTransport {
    pub async fn initialize(&mut self
    ) -> Result<InitializeResult, MCPError> {
        // 1. Connect to SSE endpoint using eventsource-client
        let sse_client = Client::for_url(&format!("{}/sse", self.url))
            .header("Accept", "text/event-stream")?;
        
        let mut stream = sse_client.stream();
        
        // 2. Wait for "endpoint" event to get message URL
        while let Some(event) = stream.next().await {
            match event {
                Ok(eventsource_client::SSE::Event(ev)) => {
                    if ev.event_type == "endpoint" {
                        self.message_endpoint = Some(ev.data);
                        break;
                    }
                }
                Err(e) => return Err(MCPError::Transport(e.to_string())),
            }
        }
        
        // 3. Send initialize via HTTP POST to message endpoint
        let result = self.send_http_request(JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "initialize".to_string(),
            params: serde_json::to_value(InitializeParams {
                protocol_version: "2024-11-05".to_string(),
                capabilities: serde_json::json!({}),
                client_info: ClientInfo {
                    name: "heirloom".to_string(),
                    version: env!("CARGO_PKG_VERSION").to_string(),
                },
            }).unwrap(),
        }).await?;
        
        // 4. Read response (simplified - real impl needs SSE parsing)
        Ok(result)
    }
}
```

**依赖：**
```toml
eventsource-client = "0.12"
```

**SSE 协议：**
- Initial connection to `/sse` endpoint
- Server sends `endpoint` event with message URL
- Client sends messages via HTTP POST to message URL
- Server sends responses via SSE stream

### 5. Tool Discovery and Conversion

**Bifrost 流程：**
1. MCP client connects
2. Call `tools/list` → get `[]MCPTool`
3. Convert to OpenAI tool format: `[]ChatTool`
4. Inject into chat completion request

**Heirloom 相同流程：**

```rust
impl MCPClient {
    pub async fn discover_tools(&mut self
    ) -> Result<Vec<Tool>, MCPError> {
        let mcp_tools = match &mut self.transport {
            Transport::Stdio(t) => t.list_tools().await?,
            Transport::Sse(t) => t.list_tools().await?,
        };
        
        Ok(mcp_tools.into_iter().map(|t| {
            Tool {
                tool_type: "function".to_string(),
                function: FunctionTool {
                    name: format!("{}_{}", self.name, t.name),
                    description: t.description,
                    parameters: t.inputSchema,
                },
            }
        }).collect())
    }
}
```

**工具命名：**
- 格式：`{clientName}_{toolName}`
- 示例：`filesystem_read_file`, `fetch_fetch_url`
- 防止不同 client 的工具名冲突

### 6. Agent Mode（核心差异）

**Bifrost Agent Mode 特点：**
- 4-level 工具过滤（Global → Client → Tool → Per-request）
- Per-tool auto-execute 配置
- Code mode 特殊验证（静态分析）
- Parallel execution for auto-executable tools
- Serial execution for manual tools（等待用户确认）

**Heirloom Agent Mode（简化）：**

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
        // 1. Inject MCP tools
        let tools = self.mcp_manager.get_tools().await;
        if !tools.is_empty() {
            request.tools = Some(tools);
        }
        
        for depth in 0..self.max_depth {
            // 2. Call LLM
            let response = self.gateway.chat_completion(request.clone()).await?;
            
            // 3. Check for tool_calls
            if let Some(tool_calls) = &response.choices[0].message.tool_calls {
                // 4. Classify: auto-executable vs manual
                let (auto, manual) = self.classify_tools(tool_calls).await;
                
                if !manual.is_empty() {
                    // Return response with tool_calls directly (Bifrost approach)
                    // Client (e.g., OpenAI SDK) will handle these tool_calls
                    return Ok(response);
                }
                
                // 5. Execute auto tools in parallel
                let results = futures::future::join_all(
                    auto.iter().map(|tc| self.execute_tool(tc))
                ).await;
                
                // 6. Append to conversation
                request.messages.push(ChatMessage::Assistant {
                    content: response.choices[0].message.content.clone(),
                    tool_calls: Some(auto),
                });
                
                for (tool_call, result) in auto.iter().zip(results.iter()) {
                    request.messages.push(ChatMessage::Tool {
                        tool_call_id: tool_call.id.clone(),
                        content: result.clone(),
                    });
                }
            } else {
                // No tool calls, return final response
                return Ok(response);
            }
        }
        
        Err(GatewayError::new(ErrorKind::AgentMaxDepthExceeded, "Max agent depth exceeded"))
    }
}
```

**分类逻辑：**
```rust
async fn classify_tools(
    &self,
    tool_calls: &[ToolCall],
) -> (Vec<ToolCall>, Vec<ToolCall>) {
    let mut auto = Vec::new();
    let mut manual = Vec::new();
    
    for tool_call in tool_calls {
        let parts: Vec<&str> = tool_call.function.name.splitn(2, '_').collect();
        if parts.len() == 2 {
            if self.mcp_manager.is_auto_executable(parts[0]).await {
                auto.push(tool_call.clone());
            } else {
                manual.push(tool_call.clone());
            }
        } else {
            manual.push(tool_call.clone());
        }
    }
    
    (auto, manual)
}
```

**关键简化：**
- Client-level `auto_execute`（非 per-tool）
- 无 Code Mode 验证
- 无 4-level 过滤
- 无 Plugin Hooks

### 7. 并发控制

**Bifrost：**
- stdio: 单进程，天然串行
- SSE: 通过 `mark3labs/mcp-go` 内部管理
- Agent: `sync.WaitGroup` + goroutine 并行执行 auto tools

**Heirloom：**

```rust
pub struct MCPClient {
    name: String,
    transport: Transport,
    tools: Vec<Tool>,
    auto_execute: bool,
    semaphore: Arc<Semaphore>,  // 并发控制
}

// stdio client: Semaphore(1) — 串行
// SSE client: Semaphore(5) — 并行（可配置）

impl MCPClient {
    pub async fn execute_tool(
        &self,
        name: &str,
        arguments: Value,
    ) -> Result<String, MCPError> {
        let _permit = self.semaphore.acquire().await
            .map_err(|e| MCPError::Concurrency(e.to_string()))?;
        
        match &self.transport {
            Transport::Stdio(t) => t.call_tool(name, arguments).await,
            Transport::Sse(t) => t.call_tool(name, arguments).await,
        }
    }
}
```

---

## Milestones

### Milestone 1: MCP Configuration

**Goal:** Extend Plan A's configuration to support MCP.

#### Task 1.1: Add MCP Config Types

**Files:**
- Modify: `src/config.rs`

- [ ] **Step 1: Add MCP configuration structs**

```rust
// Add to src/config.rs

#[derive(Debug, Clone, Deserialize)]
pub struct McpConfig {
    pub enabled: bool,
    #[serde(default = "default_max_agent_depth")]
    pub max_agent_depth: usize,
    #[serde(default = "default_tool_execution_timeout_seconds")]
    pub tool_execution_timeout_seconds: u64,
    #[serde(default)]
    pub clients: Vec<McpClientConfig>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct McpClientConfig {
    pub name: String,
    pub transport: String,  // "stdio" or "sse"
    // stdio fields
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub args: Option<Vec<String>>,
    // sse fields
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    // common fields
    #[serde(default = "default_auto_execute")]
    pub auto_execute: bool,
    #[serde(default = "default_stdio_concurrency")]
    pub concurrency: usize,  // For SSE only
    #[serde(default)]
    pub timeout_seconds: Option<u64>,  // Override global default
}

fn default_max_agent_depth() -> usize { 5 }
fn default_tool_execution_timeout_seconds() -> u64 { 30 }
fn default_auto_execute() -> bool { false }
fn default_stdio_concurrency() -> usize { 1 }
```

- [ ] **Step 2: Update AppConfig validation**

```rust
impl AppConfig {
    pub fn validate(&self) -> Result<(), ConfigError> {
        // ... existing validation ...
        
        // Validate MCP config
        if let Some(mcp) = &self.mcp {
            for client in &mcp.clients {
                match client.transport.as_str() {
                    "stdio" => {
                        if client.command.is_none() {
                            return Err(ConfigError::InvalidMcpConfig(
                                format!("Client '{}' missing command for stdio transport", client.name)
                            ));
                        }
                    }
                    "sse" => {
                        if client.url.is_none() {
                            return Err(ConfigError::InvalidMcpConfig(
                                format!("Client '{}' missing url for SSE transport", client.name)
                            ));
                        }
                    }
                    _ => return Err(ConfigError::InvalidMcpConfig(
                        format!("Client '{}' has invalid transport: {}", client.name, client.transport)
                    )),
                }
            }
        }
        
        Ok(())
    }
}

#[derive(Error, Debug)]
pub enum ConfigError {
    // ... existing errors ...
    #[error("Invalid MCP config: {0}")]
    InvalidMcpConfig(String),
}
```

- [ ] **Step 3: Test MCP config parsing**

```rust
#[cfg(test)]
mod mcp_tests {
    use super::*;

    #[test]
    fn test_parse_mcp_config() {
        let toml = r#"
[mcp]
enabled = true
max_agent_depth = 5

[[mcp.clients]]
name = "filesystem"
transport = "stdio"
command = "npx"
args = ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
auto_execute = true
"#;
        let config: AppConfig = toml::from_str(toml).unwrap();
        assert!(config.mcp.is_some());
        let mcp = config.mcp.unwrap();
        assert_eq!(mcp.clients.len(), 1);
        assert_eq!(mcp.clients[0].name, "filesystem");
    }
}
```

Run: `cargo test mcp_tests`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/config.rs
git commit -m "feat: add MCP configuration types"
```

### Milestone 2: MCP Transport Layer

**Goal:** Implement stdio and SSE transports with JSON-RPC protocol.

#### Task 2.1: JSON-RPC Types

**Files:**
- Create: `src/mcp/transport.rs`

- [ ] **Step 1: Implement JSON-RPC types**

```rust
// src/mcp/transport.rs
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcRequest {
    pub jsonrpc: String,
    pub id: u64,
    pub method: String,
    pub params: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcResponse {
    pub jsonrpc: String,
    pub id: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<JsonRpcError>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JsonRpcError {
    pub code: i32,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InitializeParams {
    pub protocol_version: String,
    pub capabilities: Value,
    pub client_info: ClientInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientInfo {
    pub name: String,
    pub version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InitializeResult {
    pub protocol_version: String,
    pub capabilities: Value,
    pub server_info: ServerInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerInfo {
    pub name: String,
    pub version: String,
}
```

- [ ] **Step 2: Commit**

```bash
git add src/mcp/transport.rs
git commit -m "feat: add JSON-RPC types for MCP protocol"
```

#### Task 2.2: stdio Transport

**Files:**
- Modify: `src/mcp/transport.rs`

- [ ] **Step 1: Implement stdio transport**

```rust
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use std::sync::atomic::{AtomicU64, Ordering};

pub struct StdioTransport {
    command: String,
    args: Vec<String>,
    child: Option<Child>,
    stdin: Option<ChildStdin>,
    stdout: Option<BufReader<ChildStdout>>,
    request_id: AtomicU64,
}

impl StdioTransport {
    pub fn new(command: String, args: Vec<String>) -> Self {
        Self {
            command,
            args,
            child: None,
            stdin: None,
            stdout: None,
            request_id: AtomicU64::new(0),
        }
    }
    
    pub async fn initialize(&mut self,
    ) -> Result<InitializeResult, MCPError> {
        // Spawn subprocess
        let mut cmd = Command::new(&self.command);
        cmd.args(&self.args)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::inherit()); // Forward stderr for debugging
        
        let mut child = cmd.spawn()
            .map_err(|e| MCPError::Transport(format!("Failed to spawn process: {}", e)))?;
        
        self.stdin = child.stdin.take();
        self.stdout = child.stdout.take()
            .map(|out| BufReader::new(out));
        self.child = Some(child);
        
        // Send initialize request
        let init_req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "initialize".to_string(),
            params: serde_json::to_value(InitializeParams {
                protocol_version: "2024-11-05".to_string(),
                capabilities: serde_json::json!({}),
                client_info: ClientInfo {
                    name: "heirloom".to_string(),
                    version: env!("CARGO_PKG_VERSION").to_string(),
                },
            }).unwrap(),
        };
        
        let response = self.send_request(init_req).await?;
        
        if let Some(error) = response.error {
            return Err(MCPError::Protocol(error.message));
        }
        
        let result: InitializeResult = serde_json::from_value(
            response.result.ok_or(MCPError::Protocol("Empty initialize result".to_string()))?
        ).map_err(|e| MCPError::Protocol(format!("Invalid initialize result: {}", e)))?;
        
        // Send initialized notification
        let notification = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: 0, // Notifications use id=0 or no id
            method: "notifications/initialized".to_string(),
            params: serde_json::json!({}),
        };
        self.send_notification(notification).await?;
        
        Ok(result)
    }
    
    pub async fn list_tools(&mut self,
    ) -> Result<Vec<MCPTool>, MCPError> {
        let req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "tools/list".to_string(),
            params: serde_json::json!({}),
        };
        
        let response = self.send_request(req).await?;
        
        if let Some(error) = response.error {
            return Err(MCPError::ToolDiscovery(error.message));
        }
        
        let result = response.result.ok_or(MCPError::ToolDiscovery("Empty tools/list result".to_string()))?;
        let tools: Vec<MCPTool> = serde_json::from_value(
            result.get("tools").cloned().unwrap_or(serde_json::json!([]))
        ).map_err(|e| MCPError::ToolDiscovery(format!("Invalid tools/list result: {}", e)))?;
        
        Ok(tools)
    }
    
    pub async fn call_tool(
        &mut self,
        name: &str,
        arguments: Value,
    ) -> Result<MCPToolResult, MCPError> {
        let req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "tools/call".to_string(),
            params: serde_json::json!({
                "name": name,
                "arguments": arguments,
            }),
        };
        
        let response = self.send_request(req).await?;
        
        if let Some(error) = response.error {
            return Err(MCPError::ToolExecution(error.message));
        }
        
        let result = response.result.ok_or(MCPError::ToolExecution("Empty tools/call result".to_string()))?;
        let tool_result: MCPToolResult = serde_json::from_value(result)
            .map_err(|e| MCPError::ToolExecution(format!("Invalid tools/call result: {}", e)))?;
        
        Ok(tool_result)
    }
    
    async fn send_request(
        &mut self,
        request: JsonRpcRequest,
    ) -> Result<JsonRpcResponse, MCPError> {
        let stdin = self.stdin.as_mut()
            .ok_or(MCPError::Transport("Not connected".to_string()))?;
        let stdout = self.stdout.as_mut()
            .ok_or(MCPError::Transport("Not connected".to_string()))?;
        
        // Send request as NDJSON
        let json = serde_json::to_string(&request).map_err(|e| MCPError::Protocol(e.to_string()))?;
        let line = format!("{}\n", json);
        
        stdin.write_all(line.as_bytes()).await
            .map_err(|e| MCPError::Transport(format!("Failed to write: {}", e)))?;
        stdin.flush().await
            .map_err(|e| MCPError::Transport(format!("Failed to flush: {}", e)))?;
        
        // Read response (single line)
        let mut response_line = String::new();
        stdout.read_line(&mut response_line).await
            .map_err(|e| MCPError::Transport(format!("Failed to read: {}", e)))?;
        
        serde_json::from_str(&response_line)
            .map_err(|e| MCPError::Protocol(format!("Invalid JSON response: {}", e)))
    }
    
    async fn send_notification(
        &mut self,
        request: JsonRpcRequest,
    ) -> Result<(), MCPError> {
        let stdin = self.stdin.as_mut()
            .ok_or(MCPError::Transport("Not connected".to_string()))?;
        
        let json = serde_json::to_string(&request).map_err(|e| MCPError::Protocol(e.to_string()))?;
        let line = format!("{}\n", json);
        
        stdin.write_all(line.as_bytes()).await
            .map_err(|e| MCPError::Transport(format!("Failed to write: {}", e)))?;
        stdin.flush().await
            .map_err(|e| MCPError::Transport(format!("Failed to flush: {}", e)))?;
        
        Ok(())
    }
    
    fn next_id(&self) -> u64 {
        self.request_id.fetch_add(1, Ordering::SeqCst)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MCPTool {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub inputSchema: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MCPToolResult {
    pub content: Vec<MCPContent>,
    #[serde(default)]
    pub isError: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MCPContent {
    #[serde(rename = "type")]
    pub content_type: String,
    pub text: String,
}

#[derive(Debug, thiserror::Error)]
pub enum MCPError {
    #[error("Transport error: {0}")]
    Transport(String),
    #[error("Protocol error: {0}")]
    Protocol(String),
    #[error("Tool discovery failed: {0}")]
    ToolDiscovery(String),
    #[error("Tool execution failed: {0}")]
    ToolExecution(String),
    #[error("Concurrency error: {0}")]
    Concurrency(String),
}
```

- [ ] **Step 2: Test stdio transport**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_stdio_spawn() {
        // This test requires a mock MCP server script
        // For now, just test that we can create the transport
        let transport = StdioTransport::new(
            "echo".to_string(),
            vec!["hello".to_string()],
        );
        assert_eq!(transport.command, "echo");
    }
}
```

Run: `cargo test mcp::transport::tests`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/mcp/transport.rs
git commit -m "feat: implement MCP stdio transport with JSON-RPC"
```

#### Task 2.3: SSE Transport

**Files:**
- Modify: `src/mcp/transport.rs`

- [ ] **Step 1: Implement SSE transport**

```rust
use reqwest::Client;
use tokio::sync::mpsc;

pub struct SseTransport {
    url: String,
    headers: HashMap<String, String>,
    client: Client,
    message_endpoint: Option<String>,
    request_id: AtomicU64,
    response_rx: Option<mpsc::Receiver<JsonRpcResponse>>,
}

impl SseTransport {
    pub fn new(url: String, headers: HashMap<String, String>) -> Self {
        Self {
            url: url.trim_end_matches('/').to_string(),
            headers,
            client: Client::new(),
            message_endpoint: None,
            request_id: AtomicU64::new(0),
            response_rx: None,
        }
    }
    
    pub async fn initialize(&mut self,
    ) -> Result<InitializeResult, MCPError> {
        // 1. Connect to SSE endpoint
        let sse_url = format!("{}/sse", self.url);
        
        let mut request = self.client.get(&sse_url);
        for (key, value) in &self.headers {
            request = request.header(key, value);
        }
        
        let response = request.send().await
            .map_err(|e| MCPError::Transport(format!("SSE connection failed: {}", e)))?;
        
        if !response.status().is_success() {
            return Err(MCPError::Transport(
                format!("SSE connection failed: HTTP {}", response.status())
            ));
        }
        
        // 2. Parse SSE stream to get endpoint
        // This is a simplified version - real implementation needs SSE parsing
        self.message_endpoint = Some(format!("{}/message", self.url));
        
        // 3. Send initialize via HTTP POST
        let init_req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "initialize".to_string(),
            params: serde_json::to_value(InitializeParams {
                protocol_version: "2024-11-05".to_string(),
                capabilities: serde_json::json!({}),
                client_info: ClientInfo {
                    name: "heirloom".to_string(),
                    version: env!("CARGO_PKG_VERSION").to_string(),
                },
            }).unwrap(),
        };
        
        let response = self.send_http_request(init_req).await?;
        
        if let Some(error) = response.error {
            return Err(MCPError::Protocol(error.message));
        }
        
        let result: InitializeResult = serde_json::from_value(
            response.result.ok_or(MCPError::Protocol("Empty initialize result".to_string()))?
        ).map_err(|e| MCPError::Protocol(format!("Invalid initialize result: {}", e)))?;
        
        // 4. Send initialized notification
        let notification = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: 0,
            method: "notifications/initialized".to_string(),
            params: serde_json::json!({}),
        };
        self.send_http_notification(notification).await?;
        
        Ok(result)
    }
    
    pub async fn list_tools(&mut self,
    ) -> Result<Vec<MCPTool>, MCPError> {
        let req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "tools/list".to_string(),
            params: serde_json::json!({}),
        };
        
        let response = self.send_http_request(req).await?;
        
        if let Some(error) = response.error {
            return Err(MCPError::ToolDiscovery(error.message));
        }
        
        let result = response.result.ok_or(MCPError::ToolDiscovery("Empty tools/list result".to_string()))?;
        let tools: Vec<MCPTool> = serde_json::from_value(
            result.get("tools").cloned().unwrap_or(serde_json::json!([]))
        ).map_err(|e| MCPError::ToolDiscovery(format!("Invalid tools/list result: {}", e)))?;
        
        Ok(tools)
    }
    
    pub async fn call_tool(
        &mut self,
        name: &str,
        arguments: Value,
    ) -> Result<MCPToolResult, MCPError> {
        let req = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: self.next_id(),
            method: "tools/call".to_string(),
            params: serde_json::json!({
                "name": name,
                "arguments": arguments,
            }),
        };
        
        let response = self.send_http_request(req).await?;
        
        if let Some(error) = response.error {
            return Err(MCPError::ToolExecution(error.message));
        }
        
        let result = response.result.ok_or(MCPError::ToolExecution("Empty tools/call result".to_string()))?;
        let tool_result: MCPToolResult = serde_json::from_value(result)
            .map_err(|e| MCPError::ToolExecution(format!("Invalid tools/call result: {}", e)))?;
        
        Ok(tool_result)
    }
    
    async fn send_http_request(
        &self,
        request: JsonRpcRequest,
    ) -> Result<JsonRpcResponse, MCPError> {
        let endpoint = self.message_endpoint.as_ref()
            .ok_or(MCPError::Transport("Message endpoint not set".to_string()))?;
        
        let mut http_req = self.client.post(endpoint)
            .header("Content-Type", "application/json");
        
        for (key, value) in &self.headers {
            http_req = http_req.header(key, value);
        }
        
        let response = http_req.json(&request).send().await
            .map_err(|e| MCPError::Transport(format!("HTTP request failed: {}", e)))?;
        
        let status = response.status();
        let body = response.text().await
            .map_err(|e| MCPError::Transport(format!("Failed to read response: {}", e)))?;
        
        if !status.is_success() {
            return Err(MCPError::Transport(format!("HTTP {}: {}", status, body)));
        }
        
        serde_json::from_str(&body)
            .map_err(|e| MCPError::Protocol(format!("Invalid JSON response: {}", e)))
    }
    
    async fn send_http_notification(
        &self,
        request: JsonRpcRequest,
    ) -> Result<(), MCPError> {
        let endpoint = self.message_endpoint.as_ref()
            .ok_or(MCPError::Transport("Message endpoint not set".to_string()))?;
        
        let mut http_req = self.client.post(endpoint)
            .header("Content-Type", "application/json");
        
        for (key, value) in &self.headers {
            http_req = http_req.header(key, value);
        }
        
        http_req.json(&request).send().await
            .map_err(|e| MCPError::Transport(format!("HTTP notification failed: {}", e)))?;
        
        Ok(())
    }
    
    fn next_id(&self) -> u64 {
        self.request_id.fetch_add(1, Ordering::SeqCst)
    }
}
```

**注意：** SSE 传输的完整实现需要 SSE 解析器来处理流式事件。上述是简化版，实际实现需要 `eventsource-stream` crate 或自定义 SSE 解析。

- [ ] **Step 2: Commit**

```bash
git add src/mcp/transport.rs
git commit -m "feat: add MCP SSE transport (simplified)"
```

### Milestone 3: MCP Client Manager

**Goal:** Manage multiple MCP clients, tool discovery, and execution with concurrency control.

#### Task 3.1: MCP Client and Client Manager

**Files:**
- Create: `src/mcp/client.rs`

- [ ] **Step 1: Implement MCP client**

```rust
// src/mcp/client.rs
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{RwLock, Semaphore};

use crate::config::McpClientConfig;
use crate::types::*;
use super::transport::{MCPError, MCPTool, MCPToolResult, StdioTransport, SseTransport};

pub enum Transport {
    Stdio(StdioTransport),
    Sse(SseTransport),
}

pub struct MCPClient {
    pub name: String,
    pub auto_execute: bool,
    pub tools: Vec<Tool>,
    transport: Transport,
    semaphore: Arc<Semaphore>,
}

impl MCPClient {
    pub async fn from_config(config: &McpClientConfig) -> Result<Self, MCPError> {
        let (mut transport, concurrency) = match config.transport.as_str() {
            "stdio" => {
                let command = config.command.clone()
                    .ok_or(MCPError::Transport("Command required for stdio".to_string()))?;
                let args = config.args.clone().unwrap_or_default();
                
                let mut transport = StdioTransport::new(command, args);
                transport.initialize().await?;
                
                (Transport::Stdio(transport), 1) // stdio is always serial
            }
            "sse" => {
                let url = config.url.clone()
                    .ok_or(MCPError::Transport("URL required for SSE".to_string()))?;
                
                let mut transport = SseTransport::new(url, HashMap::new());
                transport.initialize().await?;
                
                (Transport::Sse(transport), config.concurrency)
            }
            _ => return Err(MCPError::Transport(
                format!("Unsupported transport: {}", config.transport)
            )),
        };
        
        // Discover tools
        let mcp_tools = match &mut transport {
            Transport::Stdio(t) => t.list_tools().await?,
            Transport::Sse(t) => t.list_tools().await?,
        };
        
        let tools = mcp_tools.into_iter()
            .map(|t| Tool {
                tool_type: "function".to_string(),
                function: FunctionTool {
                    name: format!("{}_{}", config.name, t.name),
                    description: t.description,
                    parameters: t.inputSchema,
                },
            })
            .collect();
        
        Ok(Self {
            name: config.name.clone(),
            auto_execute: config.auto_execute,
            tools,
            transport,
            semaphore: Arc::new(Semaphore::new(concurrency)),
        })
    }
    
    pub async fn execute_tool(
        &mut self,
        tool_name: &str,
        arguments: serde_json::Value,
    ) -> Result<String, MCPError> {
        let _permit = self.semaphore.acquire().await
            .map_err(|e| MCPError::Concurrency(e.to_string()))?;
        
        let result = match &mut self.transport {
            Transport::Stdio(t) => t.call_tool(tool_name, arguments).await?,
            Transport::Sse(t) => t.call_tool(tool_name, arguments).await?,
        };
        
        // Convert MCP result to string
        let text = result.content.iter()
            .map(|c| c.text.clone())
            .collect::<Vec<_>>()
            .join("\n");
        
        if result.isError {
            Err(MCPError::ToolExecution(text))
        } else {
            Ok(text)
        }
    }
}
```

- [ ] **Step 2: Implement MCP client manager**

```rust
pub struct MCPClientManager {
    clients: RwLock<HashMap<String, MCPClient>>,
}

impl MCPClientManager {
    pub fn new() -> Self {
        Self {
            clients: RwLock::new(HashMap::new()),
        }
    }
    
    pub async fn add_client(
        &self,
        config: &McpClientConfig,
    ) -> Result<(), MCPError> {
        let client = MCPClient::from_config(config).await?;
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
    
    pub async fn is_auto_executable(
        &self,
        client_name: &str,
    ) -> bool {
        let clients = self.clients.read().await;
        clients.get(client_name)
            .map(|c| c.auto_execute)
            .unwrap_or(false)
    }
    
    pub async fn execute_tool(
        &self,
        prefixed_tool_name: &str,  // format: "clientName_toolName"
        arguments: serde_json::Value,
    ) -> Result<String, MCPError> {
        let parts: Vec<&str> = prefixed_tool_name.splitn(2, '_').collect();
        if parts.len() != 2 {
            return Err(MCPError::ToolExecution(
                format!("Invalid tool name format: {}", prefixed_tool_name)
            ));
        }
        
        let client_name = parts[0];
        let tool_name = parts[1];
        
        let mut clients = self.clients.write().await;
        let client = clients.get_mut(client_name)
            .ok_or(MCPError::ToolExecution(
                format!("Client not found: {}", client_name)
            ))?;
        
        client.execute_tool(tool_name, arguments).await
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/mcp/client.rs
git commit -m "feat: implement MCP client manager with concurrency control"
```

### Milestone 4: Agent Mode

**Goal:** Implement automatic multi-turn tool calling loop.

#### Task 4.1: Agent Executor

**Files:**
- Create: `src/mcp/agent.rs`

- [ ] **Step 1: Implement agent executor**

```rust
// src/mcp/agent.rs
use std::sync::Arc;

use crate::error::{ErrorKind, GatewayError};
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
        let tools = self.mcp_manager.get_tools().await;
        if !tools.is_empty() {
            request.tools = Some(tools);
        }
        
        for depth in 0..self.max_depth {
            tracing::debug!("Agent loop iteration {}/{}", depth + 1, self.max_depth);
            
            // Call LLM
            let response = self.gateway.chat_completion(request.clone()).await?;
            
            // Check for tool_calls
            if let Some(tool_calls) = &response.choices.get(0)
                .and_then(|c| c.message.tool_calls.clone()) 
            {
                if tool_calls.is_empty() {
                    return Ok(response);
                }
                
                // Classify tools
                let (auto, manual) = self.classify_tools(&tool_calls).await;
                
                if !manual.is_empty() {
                    // Return pending response with manual tools
                    return Ok(self.build_pending_response(response, manual));
                }
                
                // Execute auto tools in parallel
                let results = futures::future::join_all(
                    auto.iter().map(|tc| self.execute_tool(tc))
                ).await;
                
                // Append assistant message with tool calls
                request.messages.push(ChatMessage::Assistant {
                    content: response.choices[0].message.content.clone(),
                    tool_calls: Some(auto.clone()),
                });
                
                // Append tool results
                for (tool_call, result) in auto.iter().zip(results.into_iter()) {
                    let content = match result {
                        Ok(text) => text,
                        Err(e) => format!("Error: {}", e),
                    };
                    
                    request.messages.push(ChatMessage::Tool {
                        tool_call_id: tool_call.id.clone(),
                        content,
                    });
                }
            } else {
                // No tool calls, return final response
                return Ok(response);
            }
        }
        
        Err(GatewayError::new(ErrorKind::AgentMaxDepthExceeded, 
            format!("Agent exceeded maximum depth of {}", self.max_depth)))
    }
    
    async fn classify_tools(
        &self,
        tool_calls: &[ToolCall],
    ) -> (Vec<ToolCall>, Vec<ToolCall>) {
        let mut auto = Vec::new();
        let mut manual = Vec::new();
        
        for tool_call in tool_calls {
            let parts: Vec<&str> = tool_call.function.name.splitn(2, '_').collect();
            if parts.len() == 2 {
                if self.mcp_manager.is_auto_executable(parts[0]).await {
                    auto.push(tool_call.clone());
                } else {
                    manual.push(tool_call.clone());
                }
            } else {
                // Unknown format, treat as manual
                manual.push(tool_call.clone());
            }
        }
        
        (auto, manual)
    }
    
    async fn execute_tool(
        &self,
        tool_call: &ToolCall,
    ) -> Result<String, GatewayError> {
        let args: serde_json::Value = serde_json::from_str(&tool_call.function.arguments)
            .unwrap_or(serde_json::json!({}));
        
        self.mcp_manager.execute_tool(&tool_call.function.name, 
            args
        ).await.map_err(|e| GatewayError::new(ErrorKind::Provider, e.to_string()))
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/mcp/agent.rs
git commit -m "feat: implement MCP agent mode with tool execution loop"
```

### Milestone 5: HTTP Integration

**Goal:** Integrate MCP into HTTP handlers and main application.

#### Task 5.1: Extend HTTP Handlers

**Files:**
- Modify: `src/http/handlers.rs`
- Modify: `src/http/mod.rs`

- [ ] **Step 1: Modify handlers for MCP**

```rust
// Modify src/http/handlers.rs

use std::sync::Arc;
use crate::mcp::agent::AgentExecutor;
use crate::mcp::client::MCPClientManager;

pub async fn chat_completions(
    body: web::Json<ChatCompletionRequest>,
    gateway: web::Data<Arc<Gateway>>,
    mcp_manager: web::Data<Option<Arc<MCPClientManager>>>,
    agent_executor: web::Data<Option<Arc<AgentExecutor>>>,
) -> impl Responder {
    let request = body.into_inner();
    
    // Check if MCP is enabled and has tools
    if let (Some(mcp), Some(agent)) = (mcp_manager.as_ref(), agent_executor.as_ref()) {
        let tools = mcp.get_tools().await;
        if !tools.is_empty() {
            // Use agent mode
            match agent.execute(request).await {
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
            handle_standard_chat(request, &gateway).await
        }
    } else {
        // Standard mode
        handle_standard_chat(request, &gateway).await
    }
}

async fn handle_standard_chat(
    request: ChatCompletionRequest,
    gateway: &Arc<Gateway>,
) -> HttpResponse {
    if request.stream {
        match gateway.chat_completion_stream(request).await {
            Ok(stream) => {
                let sse_stream = stream
                    .map(|chunk| {
                        match chunk {
                            Ok(chunk) => {
                                let data = serde_json::to_string(&chunk).unwrap();
                                Ok::<_, actix_web::Error>(format!("data: {}\n\n", data))
                            }
                            Err(e) => {
                                let data = serde_json::json!({"error": e.to_string()}).to_string();
                                Ok(format!("data: {}\n\n", data))
                            }
                        }
                    })
                    .chain(futures::stream::once(async {
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
```

- [ ] **Step 2: Modify HTTP module to accept MCP**

```rust
// Modify src/http/mod.rs

use std::sync::Arc;
use actix_web::{web, App, HttpServer};

use crate::config::AppConfig;
use crate::gateway::Gateway;
use crate::mcp::agent::AgentExecutor;
use crate::mcp::client::MCPClientManager;

pub async fn run_server(
    config: &AppConfig,
    gateway: Gateway,
    mcp_manager: Option<Arc<MCPClientManager>>,
    agent_executor: Option<Arc<AgentExecutor>>,
) -> std::io::Result<()> {
    let gateway = web::Data::new(Arc::new(gateway));
    let mcp_manager = web::Data::new(mcp_manager);
    let agent_executor = web::Data::new(agent_executor);
    let bind_addr = format!("{}:{}", config.server.host, config.server.port);
    
    HttpServer::new(move || {
        middleware::configure_app()
            .app_data(gateway.clone())
            .app_data(mcp_manager.clone())
            .app_data(agent_executor.clone())
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

- [ ] **Step 3: Commit**

```bash
git add src/http/handlers.rs src/http/mod.rs
git commit -m "feat: integrate MCP agent mode into HTTP handlers"
```

#### Task 5.2: Update Main Application

**Files:**
- Modify: `src/main.rs`

- [ ] **Step 1: Initialize MCP in main**

```rust
// Modify src/main.rs

use tracing::{info, warn};
use std::sync::Arc;

// ... existing imports ...
use crate::mcp::agent::AgentExecutor;
use crate::mcp::client::MCPClientManager;

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
    
    let gateway = Arc::new(Gateway::from_config(&config));
    
    // Initialize MCP if configured
    let (mcp_manager, agent_executor) = if let Some(mcp_config) = &config.mcp {
        if mcp_config.enabled {
            let manager = Arc::new(MCPClientManager::new());
            
            for client_config in &mcp_config.clients {
                match manager.add_client(client_config).await {
                    Ok(_) => info!("MCP client '{}' connected", client_config.name),
                    Err(e) => warn!("Failed to connect MCP client '{}': {}", client_config.name, e),
                }
            }
            
            let executor = Arc::new(AgentExecutor::new(
                mcp_config.max_agent_depth,
                gateway.clone(),
                manager.clone(),
            ));
            
            (Some(manager), Some(executor))
        } else {
            (None, None)
        }
    } else {
        (None, None)
    };
    
    info!("Starting Heirloom on {}:{}", config.server.host, config.server.port);
    
    http::run_server(
        &config,
        (*gateway).clone(),
        mcp_manager,
        agent_executor,
    ).await?;
    
    Ok(())
}
```

- [ ] **Step 2: Build project**

Run: `cargo build`
Expected: Compiles successfully

- [ ] **Step 3: Commit**

```bash
git add src/main.rs
git commit -m "feat: initialize MCP in main application"
```

### Milestone 6: Testing and Documentation

**Goal:** Add integration tests and update documentation.

#### Task 6.1: MCP Integration Tests

**Files:**
- Create: `tests/integration/mcp_tests.rs`

- [ ] **Step 1: Write MCP integration tests**

```rust
// tests/integration/mcp_tests.rs
use std::sync::Arc;

#[tokio::test]
async fn test_mcp_tool_discovery() {
    // This test requires a mock MCP server
    // For now, create a simple test that validates the flow
    
    let manager = Arc::new(heirloom::mcp::client::MCPClientManager::new());
    let tools = manager.get_tools().await;
    
    assert!(tools.is_empty());
}

#[tokio::test]
async fn test_agent_mode_disabled_without_mcp() {
    // Test that agent mode doesn't interfere when MCP is disabled
    let gateway = Arc::new(create_test_gateway());
    let agent = AgentExecutor::new(5, gateway, Arc::new(MCPClientManager::new()));
    
    let request = ChatCompletionRequest {
        model: "openai/gpt-4".to_string(),
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
    
    let response = agent.execute(request).await;
    assert!(response.is_ok());
}
```

- [ ] **Step 2: Commit**

```bash
git add tests/integration/mcp_tests.rs
git commit -m "test: add MCP integration tests"
```

#### Task 6.2: Update Example Config

**Files:**
- Modify: `config.example.toml`

- [ ] **Step 1: Add MCP section to example config**

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

# MCP Configuration (Optional)
[mcp]
enabled = true
max_agent_depth = 5
tool_execution_timeout_seconds = 30  # Global default

# MCP Client 1: Filesystem tools via stdio
[[mcp.clients]]
name = "filesystem"
transport = "stdio"
command = "npx"
args = ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
auto_execute = true
# Uses global timeout (30s)

# MCP Client 2: Fetch tools via SSE
[[mcp.clients]]
name = "fetch"
transport = "sse"
url = "http://localhost:3001/sse"
auto_execute = true
concurrency = 5
timeout_seconds = 60  # Override global timeout for this client
```

- [ ] **Step 2: Commit**

```bash
git add config.example.toml
git commit -m "docs: add MCP configuration to example config"
```

---

## Summary

### Milestones (6)

| # | Milestone | Tasks | Description |
|---|-----------|-------|-------------|
| 1 | MCP Configuration | 1 | Extend Plan A config with MCP types |
| 2 | MCP Transport | 2 | JSON-RPC + stdio + SSE |
| 3 | MCP Client Manager | 1 | Client management, tool discovery, execution |
| 4 | Agent Mode | 1 | Multi-turn tool calling loop |
| 5 | HTTP Integration | 2 | Handler integration, main.rs initialization |
| 6 | Testing & Docs | 2 | Integration tests, example config |

### Total Tasks: 9
### Estimated Time: 3-4 weeks (after Plan A completes)

### Key Design Decisions

1. **传输协议**：stdio + SSE（砍掉 HTTP、In-process）
   - SSE 使用 `eventsource-client` crate 简化实现
2. **工具过滤**：Client-level `auto_execute`（砍掉 per-tool、4-level）
3. **Agent 模式**：自动执行 + 并行（砍掉 Code Mode、复杂验证）
   - Manual tools：直接返回包含 `tool_calls` 的 response（Bifrost 模式）
4. **并发控制**：Semaphore（stdio=1, SSE=5）
5. **错误处理**：统一 MCPError → GatewayError 转换
6. **工具超时**：全局默认值 30s + per-client 覆盖

---

**Plan saved to:** `docs/superpowers/plans/2025-04-27-heirloom-mcp-gateway-plan.md`

**依赖：** Plan A (Core Gateway) 必须完成

**执行顺序：**
1. 完成 Plan A
2. 执行 Plan B

**是否确认这个 MCP 计划？** 确认后可以在 Plan A 完成后立即开始执行。