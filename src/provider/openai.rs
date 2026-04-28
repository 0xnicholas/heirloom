use async_trait::async_trait;
use futures::stream::{self, BoxStream, StreamExt};
use reqwest::header::{AUTHORIZATION, CONTENT_TYPE};

use crate::client::HttpClient;
use crate::error::{ErrorKind, GatewayError};
use crate::gateway::key_selector::{select_key, WeightedKey};
use crate::provider::Provider;
use crate::types::*;

pub struct OpenAIProvider {
    base_url: String,
    keys: Vec<WeightedKey>,
    timeout_seconds: u64,
    client: HttpClient,
}

impl OpenAIProvider {
    pub fn new(
        base_url: String,
        keys: Vec<WeightedKey>,
        timeout_seconds: u64,
        extra_headers: &std::collections::HashMap<String, String>,
        proxy_url: Option<&str>,
        enforce_http2: bool,
    ) -> anyhow::Result<Self> {
        let base_url = if base_url.is_empty() {
            "https://api.openai.com".to_string()
        } else {
            base_url.trim_end_matches('/').to_string()
        };
        
        Ok(Self {
            base_url,
            keys,
            timeout_seconds,
            client: HttpClient::new(timeout_seconds, extra_headers, proxy_url, enforce_http2)?,
        })
    }
    
    fn select_key(&self) -> &WeightedKey {
        select_key(&self.keys)
    }
    
    fn auth_header(&self) -> String {
        format!("Bearer {}", self.select_key().value.expose())
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::SecretString;
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
            vec![WeightedKey { value: SecretString::new("test-key"), weight: 1.0 }],
            30,
            &std::collections::HashMap::new(),
            None,
            false,
        ).unwrap();
        
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
