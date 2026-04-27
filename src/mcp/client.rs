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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_manager_creation() {
        let _manager = MCPClientManager::new();
        // Just verify it can be created without panic
    }
}
