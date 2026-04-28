use async_trait::async_trait;
use futures::stream::BoxStream;

use crate::error::GatewayError;
use crate::gateway::key_selector::WeightedKey;
use crate::provider::{openai::OpenAIProvider, Provider};
use crate::types::*;

pub struct GroqProvider {
    inner: OpenAIProvider,
}

impl GroqProvider {
    pub fn new(
        base_url: String,
        keys: Vec<WeightedKey>,
        timeout_seconds: u64,
        extra_headers: &std::collections::HashMap<String, String>,
        proxy_url: Option<&str>,
        enforce_http2: bool,
    ) -> anyhow::Result<Self> {
        Ok(Self {
            inner: OpenAIProvider::new(
                base_url,
                keys,
                timeout_seconds,
                extra_headers,
                proxy_url,
                enforce_http2,
            )?,
        })
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::SecretString;

    #[test]
    fn test_groq_name() {
        let provider = GroqProvider::new(
            "https://api.groq.com".to_string(),
            vec![WeightedKey {
                value: SecretString::new("test-key"),
                weight: 1.0,
            }],
            30,
            &std::collections::HashMap::new(),
            None,
            false,
        )
        .unwrap();
        assert_eq!(provider.name(), "groq");
    }
}
