pub mod key_selector;
pub mod retry;
pub mod fallback;
pub mod queue;

pub use key_selector::*;
pub use retry::*;
pub use fallback::*;
pub use queue::*;

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use crate::config::AppConfig;
use crate::error::{ErrorKind, GatewayError};
use crate::provider::{openai::OpenAIProvider, anthropic::AnthropicProvider, groq::GroqProvider, ProviderRef};
use crate::gateway::key_selector::WeightedKey;
use crate::gateway::queue::{GatewayRequest, GatewayResponse, ProviderQueue};
use crate::gateway::retry::RetryPolicy;
use crate::types::*;

pub struct Gateway {
    queues: HashMap<String, ProviderQueue>,
}

impl Gateway {
    pub fn from_config(config: &AppConfig) -> anyhow::Result<Self> {
        let mut queues = HashMap::new();
        
        for (name, provider_config) in &config.providers {
            if !provider_config.enabled {
                continue;
            }
            
            let keys: Vec<WeightedKey> = provider_config.keys.iter()
                .map(|k| WeightedKey { value: k.value.clone(), weight: k.weight })
                .collect();
            
            let provider: ProviderRef = match name.as_str() {
                "openai" => Arc::new(OpenAIProvider::new(
                    provider_config.base_url.clone(),
                    keys,
                    provider_config.request_timeout_seconds,
                )),
                "anthropic" => Arc::new(AnthropicProvider::new(
                    provider_config.base_url.clone(),
                    keys,
                    provider_config.request_timeout_seconds,
                )),
                "groq" => Arc::new(GroqProvider::new(
                    provider_config.base_url.clone(),
                    keys,
                    provider_config.request_timeout_seconds,
                )),
                _ => {
                    tracing::warn!("Unknown provider: {}, skipping", name);
                    continue;
                }
            };
            
            let retry_policy = RetryPolicy::new(
                provider_config.max_retries,
                Duration::from_millis(provider_config.retry_backoff_initial_ms),
                Duration::from_millis(provider_config.retry_backoff_max_ms),
            );
            
            let queue = ProviderQueue::new(
                provider,
                retry_policy,
                provider_config.queue_concurrency,
                provider_config.queue_buffer_size,
            );
            
            queues.insert(name.clone(), queue);
        }
        
        Ok(Self { queues })
    }
    
    pub async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        // Extract provider from model string (e.g., "openai/gpt-4" or "gpt-4")
        let provider_name = Self::resolve_provider(&request.model);
        
        let queue = self.queues.get(&provider_name)
            .ok_or_else(|| GatewayError::new(
                ErrorKind::NoProviderAvailable,
                format!("Provider '{}' not found", provider_name)
            ))?;
        
        match queue.send(GatewayRequest::ChatCompletion(request)).await? {
            GatewayResponse::ChatCompletion(result) => result,
            _ => Err(GatewayError::new(ErrorKind::Provider, "Unexpected response type")),
        }
    }
    
    pub async fn chat_completion_stream(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<futures::stream::BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
        let provider_name = Self::resolve_provider(&request.model);
        
        let queue = self.queues.get(&provider_name)
            .ok_or_else(|| GatewayError::new(
                ErrorKind::NoProviderAvailable,
                format!("Provider '{}' not found", provider_name)
            ))?;
        
        // For streaming, we need direct provider access
        // This is a simplified version - in production, you'd route through queue
        Err(GatewayError::new(ErrorKind::Provider, "Streaming through queue not yet implemented"))
    }
    
    pub async fn embedding(
        &self,
        request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError> {
        let provider_name = Self::resolve_provider(&request.model);
        
        let queue = self.queues.get(&provider_name)
            .ok_or_else(|| GatewayError::new(
                ErrorKind::NoProviderAvailable,
                format!("Provider '{}' not found", provider_name)
            ))?;
        
        match queue.send(GatewayRequest::Embedding(request)).await? {
            GatewayResponse::Embedding(result) => result,
            _ => Err(GatewayError::new(ErrorKind::Provider, "Unexpected response type")),
        }
    }
    
    pub async fn list_models(&self) -> Result<ModelList, GatewayError> {
        // Aggregate models from all providers
        let mut all_models = Vec::new();
        
        for (name, queue) in &self.queues {
            match queue.send(GatewayRequest::ListModels).await {
                Ok(GatewayResponse::ListModels(Ok(list))) => {
                    all_models.extend(list.data);
                }
                Ok(_) | Err(_) => {
                    tracing::warn!("Failed to list models for provider: {}", name);
                }
            }
        }
        
        Ok(ModelList {
            object: "list".to_string(),
            data: all_models,
        })
    }
    
    fn resolve_provider(model: &str) -> String {
        // Parse "provider/model" or just "model"
        if let Some(pos) = model.find('/') {
            model[..pos].to_string()
        } else {
            // Default to openai if no provider specified
            "openai".to_string()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_resolve_provider_explicit() {
        assert_eq!(Gateway::resolve_provider("openai/gpt-4"), "openai");
        assert_eq!(Gateway::resolve_provider("anthropic/claude-3"), "anthropic");
    }

    #[test]
    fn test_resolve_provider_default() {
        assert_eq!(Gateway::resolve_provider("gpt-4"), "openai");
    }
}
