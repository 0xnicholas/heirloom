use async_trait::async_trait;
use futures::stream::BoxStream;
use reqwest::header::{HeaderMap, HeaderValue, CONTENT_TYPE};

use crate::client::HttpClient;
use crate::error::{ErrorKind, GatewayError};
use crate::gateway::key_selector::{select_key, WeightedKey};
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
        headers.insert("x-api-key", HeaderValue::from_str(self.select_key().value.expose()).unwrap());
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
            top_p: None,
            frequency_penalty: None,
            presence_penalty: None,
            stop: None,
            tools: None,
            tool_choice: None,
            user: None,
        };
        
        let anthropic = AnthropicProvider::convert_request(&request);
        assert_eq!(anthropic.model, "claude-3-opus");
        assert_eq!(anthropic.system, Some("You are helpful".to_string()));
        assert_eq!(anthropic.messages.len(), 1);
        assert_eq!(anthropic.messages[0].role, "user");
    }
}
