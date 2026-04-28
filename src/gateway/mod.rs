pub mod key_selector;
pub mod retry;
pub mod fallback;
pub mod queue;
pub mod rate_limit;

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
    providers: HashMap<String, ProviderRef>,
    fallbacks: HashMap<String, FallbackChain>,
    model_map: HashMap<String, String>, // model_name -> provider_name
}

impl Gateway {
    pub fn from_config(config: &AppConfig) -> anyhow::Result<Self> {
        // First, create all providers
        let mut providers = HashMap::new();
        for (name, provider_config) in &config.providers {
            if !provider_config.enabled {
                continue;
            }
            
            let keys: Vec<WeightedKey> = provider_config.keys.iter()
                .map(|k| WeightedKey { value: k.value.clone(), weight: k.weight })
                .collect();
            
            let proxy_url = provider_config.network.proxy_url.as_deref();
            let provider: ProviderRef = match name.as_str() {
                "openai" => Arc::new(OpenAIProvider::new(
                    provider_config.base_url.clone(),
                    keys,
                    provider_config.request_timeout_seconds,
                    &provider_config.network.extra_headers,
                    proxy_url,
                    provider_config.network.enforce_http2,
                )?),
                "anthropic" => Arc::new(AnthropicProvider::new(
                    provider_config.base_url.clone(),
                    keys,
                    provider_config.request_timeout_seconds,
                    &provider_config.network.extra_headers,
                    proxy_url,
                    provider_config.network.enforce_http2,
                )?),
                "groq" => Arc::new(GroqProvider::new(
                    provider_config.base_url.clone(),
                    keys,
                    provider_config.request_timeout_seconds,
                    &provider_config.network.extra_headers,
                    proxy_url,
                    provider_config.network.enforce_http2,
                )?),
                _ => {
                    tracing::warn!("Unknown provider: {}, skipping", name);
                    continue;
                }
            };
            
            providers.insert(name.clone(), provider);
        }
        
        // Create queues using cloned provider references
        let mut queues = HashMap::new();
        for (name, provider_config) in &config.providers {
            if !provider_config.enabled {
                continue;
            }
            
            let provider = providers.get(name)
                .ok_or_else(|| anyhow::anyhow!("Provider '{}' not found in provider map", name))?;
            
            let retry_policy = RetryPolicy::new(
                provider_config.max_retries,
                Duration::from_millis(provider_config.retry_backoff_initial_ms),
                Duration::from_millis(provider_config.retry_backoff_max_ms),
            );
            
            let queue = ProviderQueue::new(
                provider.clone(),
                retry_policy,
                provider_config.queue_concurrency,
                provider_config.queue_buffer_size,
            );
            
            queues.insert(name.clone(), queue);
        }
        
        // Build fallback chains
        let mut fallbacks = HashMap::new();
        for chain_config in &config.fallbacks {
            let primary = providers.get(&chain_config.primary)
                .ok_or_else(|| anyhow::anyhow!("Fallback primary provider '{}' not found", chain_config.primary))?;
            
            let mut fallback_providers = Vec::new();
            for fallback_name in &chain_config.fallbacks {
                let fallback = providers.get(fallback_name)
                    .ok_or_else(|| anyhow::anyhow!("Fallback provider '{}' not found", fallback_name))?;
                fallback_providers.push(fallback.clone());
            }
            
            let chain = FallbackChain::new(primary.clone(), fallback_providers);
            fallbacks.insert(chain_config.primary.clone(), chain);
        }
        
        let model_map = config.models.clone();
        
        Ok(Self { queues, providers, fallbacks, model_map })
    }
    
    pub async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        let provider_name = self.resolve_provider(&request.model);
        
        // Check if there's a fallback chain for this provider
        if let Some(chain) = self.fallbacks.get(&provider_name) {
            return chain.execute_chat_completion(request).await;
        }
        
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
        let provider_name = self.resolve_provider(&request.model);
        
        let provider = self.providers.get(&provider_name)
            .ok_or_else(|| GatewayError::new(
                ErrorKind::NoProviderAvailable,
                format!("Provider '{}' not found", provider_name)
            ))?;
        
        // Direct provider call for streaming (bypasses queue)
        provider.chat_completion_stream(request).await
    }
    
    pub async fn embedding(
        &self,
        request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError> {
        let provider_name = self.resolve_provider(&request.model);
        
        // Check if there's a fallback chain for this provider
        if let Some(chain) = self.fallbacks.get(&provider_name) {
            return chain.execute_embedding(request).await;
        }
        
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
    
    fn resolve_provider(&self, model: &str) -> String {
        if let Some(pos) = model.find('/') {
            model[..pos].to_string()
        } else if let Some(provider) = self.model_map.get(model) {
            provider.clone()
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
        let gateway = Gateway {
            queues: HashMap::new(),
            providers: HashMap::new(),
            fallbacks: HashMap::new(),
            model_map: HashMap::new(),
        };
        assert_eq!(gateway.resolve_provider("openai/gpt-4"), "openai");
        assert_eq!(gateway.resolve_provider("anthropic/claude-3"), "anthropic");
    }

    #[test]
    fn test_resolve_provider_default() {
        let gateway = Gateway {
            queues: HashMap::new(),
            providers: HashMap::new(),
            fallbacks: HashMap::new(),
            model_map: HashMap::new(),
        };
        assert_eq!(gateway.resolve_provider("gpt-4"), "openai");
    }

    #[test]
    fn test_resolve_provider_mapped() {
        let mut model_map = HashMap::new();
        model_map.insert("claude-3-opus".to_string(), "anthropic".to_string());
        
        let gateway = Gateway {
            queues: HashMap::new(),
            providers: HashMap::new(),
            fallbacks: HashMap::new(),
            model_map,
        };
        assert_eq!(gateway.resolve_provider("claude-3-opus"), "anthropic");
    }
}
