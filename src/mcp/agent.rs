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
    // Estimated token limits for common models (conservative estimates)
    const DEFAULT_MAX_TOKENS: usize = 4000;
    const MAX_MESSAGES: usize = 50; // Prevent memory explosion

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

    /// Estimate token count from text (rough approximation: 1 token ≈ 4 chars)
    fn estimate_tokens(text: &str) -> usize {
        text.len() / 4 + 1
    }

    /// Calculate total estimated tokens for all messages
    fn estimate_message_tokens(messages: &[ChatMessage]) -> usize {
        messages
            .iter()
            .map(|m| match m {
                ChatMessage::System { content } => Self::estimate_tokens(content),
                ChatMessage::User { content } => Self::estimate_tokens(content),
                ChatMessage::Assistant {
                    content,
                    tool_calls,
                } => {
                    let content_tokens = content
                        .as_ref()
                        .map(|c| Self::estimate_tokens(c))
                        .unwrap_or(0);
                    let tool_tokens = tool_calls
                        .as_ref()
                        .map(|tc| {
                            tc.iter()
                                .map(|t| {
                                    Self::estimate_tokens(&t.function.name)
                                        + Self::estimate_tokens(&t.function.arguments.to_string())
                                })
                                .sum()
                        })
                        .unwrap_or(0);
                    content_tokens + tool_tokens
                }
                ChatMessage::Tool { content, .. } => Self::estimate_tokens(content),
            })
            .sum()
    }

    /// Truncate messages to fit within token limit while preserving system message
    fn truncate_messages(messages: &mut Vec<ChatMessage>, max_tokens: usize) {
        if Self::estimate_message_tokens(messages) <= max_tokens {
            return;
        }

        // Keep system message at index 0 if present
        let has_system = messages
            .first()
            .map(|m| matches!(m, ChatMessage::System { .. }))
            .unwrap_or(false);

        let start_idx = if has_system { 1 } else { 0 };

        // Remove oldest non-system messages until under limit
        while messages.len() > start_idx + 1 && Self::estimate_message_tokens(messages) > max_tokens
        {
            messages.remove(start_idx);
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

        // Check initial message count
        if request.messages.len() > Self::MAX_MESSAGES {
            tracing::warn!(
                "Initial messages ({}) exceed MAX_MESSAGES ({}), truncating",
                request.messages.len(),
                Self::MAX_MESSAGES
            );
            let start = request.messages.len() - Self::MAX_MESSAGES;
            request.messages = request.messages.split_off(start);
        }

        for depth in 0..self.max_depth {
            tracing::debug!("Agent loop iteration {}/{}", depth + 1, self.max_depth);

            // Truncate messages to prevent context window overflow
            Self::truncate_messages(&mut request.messages, Self::DEFAULT_MAX_TOKENS);

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

                // Check message count limit
                if request.messages.len() > Self::MAX_MESSAGES {
                    tracing::warn!(
                        "Messages ({}) exceeded MAX_MESSAGES ({}), truncating",
                        request.messages.len(),
                        Self::MAX_MESSAGES
                    );
                    let start = request.messages.len() - Self::MAX_MESSAGES;
                    request.messages = request.messages.split_off(start);
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
        let args = tool_call.function.arguments.clone();

        self.mcp_manager
            .execute_tool(&tool_call.function.name, args)
            .await
            .map_err(|e| GatewayError::new(ErrorKind::Provider, e.to_string()))
    }

    fn build_pending_response(
        &self,
        mut response: ChatCompletionResponse,
        manual_tools: Vec<ToolCall>,
    ) -> ChatCompletionResponse {
        // Mark response as having pending tool calls that require user approval
        // Set finish_reason to "tool_calls" to indicate incomplete processing
        if let Some(choice) = response.choices.first_mut() {
            choice.finish_reason = Some("tool_calls".to_string());
            // Ensure tool_calls are preserved in the message
            if choice.message.tool_calls.is_none() {
                choice.message.tool_calls = Some(manual_tools);
            }
        }

        // Add metadata to indicate pending manual tools
        response
    }
}
