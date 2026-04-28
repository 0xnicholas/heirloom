use std::sync::Arc;

use super::client::MCPClientManager;
use crate::error::{ErrorKind, GatewayError};
use crate::gateway::Gateway;
use crate::types::*;

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
        Self {
            max_depth,
            gateway,
            mcp_manager,
        }
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
            if let Some(tool_calls) = &response
                .choices
                .get(0)
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
                let results =
                    futures::future::join_all(auto.iter().map(|tc| self.execute_tool(tc))).await;

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

        Err(GatewayError::new(
            ErrorKind::Provider,
            format!("Agent exceeded maximum depth of {}", self.max_depth),
        ))
    }

    async fn classify_tools(&self, tool_calls: &[ToolCall]) -> (Vec<ToolCall>, Vec<ToolCall>) {
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

    async fn execute_tool(&self, tool_call: &ToolCall) -> Result<String, GatewayError> {
        let args: serde_json::Value =
            serde_json::from_str(&tool_call.function.arguments).unwrap_or(serde_json::json!({}));

        self.mcp_manager
            .execute_tool(&tool_call.function.name, args)
            .await
            .map_err(|e| GatewayError::new(ErrorKind::Provider, e.to_string()))
    }

    fn build_pending_response(
        &self,
        response: ChatCompletionResponse,
        _manual_tools: Vec<ToolCall>,
    ) -> ChatCompletionResponse {
        // Return the response as-is with tool_calls included
        // The client (e.g., OpenAI SDK) will handle these tool_calls
        response
    }
}
