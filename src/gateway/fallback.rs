use crate::error::GatewayError;
use crate::provider::ProviderRef;
use crate::types::*;
use futures::stream::BoxStream;

pub struct FallbackChain {
    primary: ProviderRef,
    fallbacks: Vec<ProviderRef>,
}

impl FallbackChain {
    pub fn new(primary: ProviderRef, fallbacks: Vec<ProviderRef>) -> Self {
        Self { primary, fallbacks }
    }

    pub async fn execute_chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError> {
        // Try primary first
        match self.primary.chat_completion(request.clone()).await {
            Ok(response) => return Ok(response),
            Err(e) => {
                if !Self::should_fallback(&e) {
                    return Err(e);
                }
            }
        }

        // Try fallbacks in order
        for (index, fallback) in self.fallbacks.iter().enumerate() {
            match fallback.chat_completion(request.clone()).await {
                Ok(response) => return Ok(response),
                Err(e) => {
                    if !Self::should_fallback(&e) || index == self.fallbacks.len() - 1 {
                        return Err(e.with_fallback_index(index as u32 + 1));
                    }
                }
            }
        }

        Err(GatewayError::new(
            crate::error::ErrorKind::NoProviderAvailable,
            "All providers in fallback chain failed",
        ))
    }

    pub async fn execute_chat_completion_stream(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError> {
        // Try primary first
        match self.primary.chat_completion_stream(request.clone()).await {
            Ok(stream) => return Ok(stream),
            Err(e) => {
                if !Self::should_fallback(&e) {
                    return Err(e);
                }
            }
        }

        // Try fallbacks in order
        for (index, fallback) in self.fallbacks.iter().enumerate() {
            match fallback.chat_completion_stream(request.clone()).await {
                Ok(stream) => return Ok(stream),
                Err(e) => {
                    if !Self::should_fallback(&e) || index == self.fallbacks.len() - 1 {
                        return Err(e.with_fallback_index(index as u32 + 1));
                    }
                }
            }
        }

        Err(GatewayError::new(
            crate::error::ErrorKind::NoProviderAvailable,
            "All providers in fallback chain failed",
        ))
    }

    pub async fn execute_embedding(
        &self,
        request: EmbeddingRequest,
    ) -> Result<EmbeddingResponse, GatewayError> {
        match self.primary.embedding(request.clone()).await {
            Ok(response) => return Ok(response),
            Err(e) => {
                if !Self::should_fallback(&e) {
                    return Err(e);
                }
            }
        }

        for (index, fallback) in self.fallbacks.iter().enumerate() {
            match fallback.embedding(request.clone()).await {
                Ok(response) => return Ok(response),
                Err(e) => {
                    if !Self::should_fallback(&e) || index == self.fallbacks.len() - 1 {
                        return Err(e.with_fallback_index(index as u32 + 1));
                    }
                }
            }
        }

        Err(GatewayError::new(
            crate::error::ErrorKind::NoProviderAvailable,
            "All providers in fallback chain failed",
        ))
    }

    fn should_fallback(error: &GatewayError) -> bool {
        // Fallback on retryable errors
        error.is_retryable()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::{ErrorKind, GatewayError};

    #[test]
    fn test_should_fallback_on_retryable() {
        let err = GatewayError::new(ErrorKind::Network, "timeout".to_string());
        assert!(FallbackChain::should_fallback(&err));

        let err = GatewayError::new(ErrorKind::RateLimited, "too many requests".to_string());
        assert!(FallbackChain::should_fallback(&err));

        let err = GatewayError::new(ErrorKind::InvalidRequest, "bad request".to_string());
        assert!(!FallbackChain::should_fallback(&err));
    }
}
