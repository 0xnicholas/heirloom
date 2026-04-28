use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};

// JSON-RPC 2.0 Types
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

// MCP Tool Types
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

// MCP Error Types
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
    #[error("Timeout after {0}s")]
    Timeout(u64),
}

// stdio Transport
pub struct StdioTransport {
    command: String,
    args: Vec<String>,
    child: Option<Child>,
    stdin: Option<ChildStdin>,
    stdout: Option<BufReader<ChildStdout>>,
    request_id: AtomicU64,
    #[allow(dead_code)]
    timeout_seconds: u64,
}

impl Drop for StdioTransport {
    fn drop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.start_kill();
            let _ = child.wait();
        }
    }
}

impl StdioTransport {
    pub fn new(command: String, args: Vec<String>, timeout_seconds: u64) -> Self {
        Self {
            command,
            args,
            child: None,
            stdin: None,
            stdout: None,
            request_id: AtomicU64::new(0),
            timeout_seconds,
        }
    }

    pub async fn initialize(&mut self) -> Result<InitializeResult, MCPError> {
        // Spawn subprocess
        let mut cmd = Command::new(&self.command);
        cmd.args(&self.args)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::inherit());

        let mut child = cmd
            .spawn()
            .map_err(|e| MCPError::Transport(format!("Failed to spawn process: {}", e)))?;

        self.stdin = child.stdin.take();
        self.stdout = child.stdout.take().map(|out| BufReader::new(out));
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
            })
            .unwrap(),
        };

        let response = self.send_request(init_req).await?;

        if let Some(error) = response.error {
            return Err(MCPError::Protocol(error.message));
        }

        let result: InitializeResult = serde_json::from_value(
            response
                .result
                .ok_or(MCPError::Protocol("Empty initialize result".to_string()))?,
        )
        .map_err(|e| MCPError::Protocol(format!("Invalid initialize result: {}", e)))?;

        // Send initialized notification
        let notification = JsonRpcRequest {
            jsonrpc: "2.0".to_string(),
            id: 0,
            method: "notifications/initialized".to_string(),
            params: serde_json::json!({}),
        };
        self.send_notification(notification).await?;

        Ok(result)
    }

    pub async fn list_tools(&mut self) -> Result<Vec<MCPTool>, MCPError> {
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

        let result = response.result.ok_or(MCPError::ToolDiscovery(
            "Empty tools/list result".to_string(),
        ))?;
        let tools: Vec<MCPTool> = serde_json::from_value(
            result
                .get("tools")
                .cloned()
                .unwrap_or(serde_json::json!([])),
        )
        .map_err(|e| MCPError::ToolDiscovery(format!("Invalid tools/list result: {}", e)))?;

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

        let result = response.result.ok_or(MCPError::ToolExecution(
            "Empty tools/call result".to_string(),
        ))?;
        let tool_result: MCPToolResult = serde_json::from_value(result)
            .map_err(|e| MCPError::ToolExecution(format!("Invalid tools/call result: {}", e)))?;

        Ok(tool_result)
    }

    async fn send_request(&mut self, request: JsonRpcRequest) -> Result<JsonRpcResponse, MCPError> {
        let stdin = self
            .stdin
            .as_mut()
            .ok_or(MCPError::Transport("Not connected".to_string()))?;
        let stdout = self
            .stdout
            .as_mut()
            .ok_or(MCPError::Transport("Not connected".to_string()))?;

        // Send request as NDJSON
        let json =
            serde_json::to_string(&request).map_err(|e| MCPError::Protocol(e.to_string()))?;
        let line = format!("{}\n", json);

        stdin
            .write_all(line.as_bytes())
            .await
            .map_err(|e| MCPError::Transport(format!("Failed to write: {}", e)))?;
        stdin
            .flush()
            .await
            .map_err(|e| MCPError::Transport(format!("Failed to flush: {}", e)))?;

        // Read response (single line)
        let mut response_line = String::new();
        stdout
            .read_line(&mut response_line)
            .await
            .map_err(|e| MCPError::Transport(format!("Failed to read: {}", e)))?;

        serde_json::from_str(&response_line)
            .map_err(|e| MCPError::Protocol(format!("Invalid JSON response: {}", e)))
    }

    async fn send_notification(&mut self, request: JsonRpcRequest) -> Result<(), MCPError> {
        let stdin = self
            .stdin
            .as_mut()
            .ok_or(MCPError::Transport("Not connected".to_string()))?;

        let json =
            serde_json::to_string(&request).map_err(|e| MCPError::Protocol(e.to_string()))?;
        let line = format!("{}\n", json);

        stdin
            .write_all(line.as_bytes())
            .await
            .map_err(|e| MCPError::Transport(format!("Failed to write: {}", e)))?;
        stdin
            .flush()
            .await
            .map_err(|e| MCPError::Transport(format!("Failed to flush: {}", e)))?;

        Ok(())
    }

    fn next_id(&self) -> u64 {
        self.request_id.fetch_add(1, Ordering::SeqCst)
    }
}

// SSE Transport
pub struct SseTransport {
    url: String,
    headers: HashMap<String, String>,
    client: reqwest::Client,
    message_endpoint: Option<String>,
    request_id: AtomicU64,
    timeout_seconds: u64,
}

impl SseTransport {
    pub fn new(url: String, headers: HashMap<String, String>, timeout_seconds: u64) -> Self {
        Self {
            url: url.trim_end_matches('/').to_string(),
            headers,
            client: reqwest::Client::new(),
            message_endpoint: None,
            request_id: AtomicU64::new(0),
            timeout_seconds,
        }
    }

    pub async fn initialize(&mut self) -> Result<InitializeResult, MCPError> {
        let timeout = tokio::time::Duration::from_secs(self.timeout_seconds);

        // 1. Connect to SSE endpoint
        let sse_url = format!("{}/sse", self.url);

        let mut request = self.client.get(&sse_url);
        for (key, value) in &self.headers {
            request = request.header(key, value);
        }

        let response = tokio::time::timeout(timeout, request.send())
            .await
            .map_err(|_| MCPError::Timeout(self.timeout_seconds))?
            .map_err(|e| MCPError::Transport(format!("SSE connection failed: {}", e)))?;

        if !response.status().is_success() {
            return Err(MCPError::Transport(format!(
                "SSE connection failed: HTTP {}",
                response.status()
            )));
        }

        // Parse SSE stream to find the message endpoint
        let bytes = tokio::time::timeout(timeout, response.bytes())
            .await
            .map_err(|_| MCPError::Timeout(self.timeout_seconds))?
            .map_err(|e| MCPError::Transport(format!("Failed to read SSE stream: {}", e)))?;

        let text = String::from_utf8_lossy(&bytes);
        let mut endpoint = None;
        let mut expecting_endpoint_data = false;

        for line in text.lines() {
            if line.starts_with("event: endpoint") {
                expecting_endpoint_data = true;
                continue;
            }
            if expecting_endpoint_data && line.starts_with("data: ") {
                endpoint = Some(line[6..].to_string());
                break;
            }
            if line.is_empty() {
                expecting_endpoint_data = false;
            }
        }

        // If no endpoint event found, default to /message relative to base URL
        let message_endpoint = match endpoint {
            Some(ep) => {
                if ep.starts_with("http://") || ep.starts_with("https://") {
                    ep
                } else {
                    // Relative path - resolve against base URL
                    format!("{}/{}", self.url, ep.trim_start_matches('/'))
                }
            }
            None => format!("{}/message", self.url),
        };

        self.message_endpoint = Some(message_endpoint);

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
            })
            .unwrap(),
        };

        let response = self.send_http_request(init_req).await?;

        if let Some(error) = response.error {
            return Err(MCPError::Protocol(error.message));
        }

        let result: InitializeResult = serde_json::from_value(
            response
                .result
                .ok_or(MCPError::Protocol("Empty initialize result".to_string()))?,
        )
        .map_err(|e| MCPError::Protocol(format!("Invalid initialize result: {}", e)))?;

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

    pub async fn list_tools(&mut self) -> Result<Vec<MCPTool>, MCPError> {
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

        let result = response.result.ok_or(MCPError::ToolDiscovery(
            "Empty tools/list result".to_string(),
        ))?;
        let tools: Vec<MCPTool> = serde_json::from_value(
            result
                .get("tools")
                .cloned()
                .unwrap_or(serde_json::json!([])),
        )
        .map_err(|e| MCPError::ToolDiscovery(format!("Invalid tools/list result: {}", e)))?;

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

        let result = response.result.ok_or(MCPError::ToolExecution(
            "Empty tools/call result".to_string(),
        ))?;
        let tool_result: MCPToolResult = serde_json::from_value(result)
            .map_err(|e| MCPError::ToolExecution(format!("Invalid tools/call result: {}", e)))?;

        Ok(tool_result)
    }

    async fn send_http_request(
        &self,
        request: JsonRpcRequest,
    ) -> Result<JsonRpcResponse, MCPError> {
        let endpoint = self
            .message_endpoint
            .as_ref()
            .ok_or(MCPError::Transport("Message endpoint not set".to_string()))?;

        let mut http_req = self
            .client
            .post(endpoint)
            .header("Content-Type", "application/json");

        for (key, value) in &self.headers {
            http_req = http_req.header(key, value);
        }

        let timeout = tokio::time::Duration::from_secs(self.timeout_seconds);
        let response = tokio::time::timeout(timeout, http_req.json(&request).send())
            .await
            .map_err(|_| MCPError::Timeout(self.timeout_seconds))?
            .map_err(|e| MCPError::Transport(format!("HTTP request failed: {}", e)))?;

        let status = response.status();
        let body = tokio::time::timeout(timeout, response.text())
            .await
            .map_err(|_| MCPError::Timeout(self.timeout_seconds))?
            .map_err(|e| MCPError::Transport(format!("Failed to read response: {}", e)))?;

        if !status.is_success() {
            return Err(MCPError::Transport(format!("HTTP {}: {}", status, body)));
        }

        serde_json::from_str(&body)
            .map_err(|e| MCPError::Protocol(format!("Invalid JSON response: {}", e)))
    }

    async fn send_http_notification(&self, request: JsonRpcRequest) -> Result<(), MCPError> {
        let endpoint = self
            .message_endpoint
            .as_ref()
            .ok_or(MCPError::Transport("Message endpoint not set".to_string()))?;

        let mut http_req = self
            .client
            .post(endpoint)
            .header("Content-Type", "application/json");

        for (key, value) in &self.headers {
            http_req = http_req.header(key, value);
        }

        http_req
            .json(&request)
            .send()
            .await
            .map_err(|e| MCPError::Transport(format!("HTTP notification failed: {}", e)))?;

        Ok(())
    }

    fn next_id(&self) -> u64 {
        self.request_id.fetch_add(1, Ordering::SeqCst)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_stdio_creation() {
        let transport = StdioTransport::new("echo".to_string(), vec!["hello".to_string()], 30);
        assert_eq!(transport.command, "echo");
    }
}
