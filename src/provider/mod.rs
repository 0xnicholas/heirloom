use async_trait::async_trait;
use futures::stream::BoxStream;
use std::sync::Arc;

use crate::error::GatewayError;
use crate::types::*;

#[async_trait]
pub trait Provider: Send + Sync {
    fn name(&self) -> &'static str;

    async fn chat_completion(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<ChatCompletionResponse, GatewayError>;

    async fn chat_completion_stream(
        &self,
        request: ChatCompletionRequest,
    ) -> Result<BoxStream<'static, Result<ChatCompletionChunk, GatewayError>>, GatewayError>;

    async fn embedding(&self, request: EmbeddingRequest)
        -> Result<EmbeddingResponse, GatewayError>;

    async fn list_models(&self) -> Result<ModelList, GatewayError>;
}

pub type ProviderRef = Arc<dyn Provider>;

pub mod anthropic;
pub mod groq;
pub mod openai;
