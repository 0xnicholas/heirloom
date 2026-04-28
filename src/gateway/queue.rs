use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::{oneshot, Semaphore};

use super::plugin::PluginPipeline;
use super::retry::RetryPolicy;
use crate::error::GatewayError;
use crate::provider::ProviderRef;
use crate::types::*;

pub enum GatewayRequest {
    ChatCompletion(ChatCompletionRequest),
    Embedding(EmbeddingRequest),
    ListModels,
}

pub enum GatewayResponse {
    ChatCompletion(Result<ChatCompletionResponse, GatewayError>),
    Embedding(Result<EmbeddingResponse, GatewayError>),
    ListModels(Result<ModelList, GatewayError>),
}

pub struct ProviderQueue {
    provider: ProviderRef,
    retry_policy: RetryPolicy,
    plugin_pipeline: Option<PluginPipeline>,
    semaphore: Arc<Semaphore>,
    closing: AtomicBool,
}

impl ProviderQueue {
    pub fn new(
        provider: ProviderRef,
        retry_policy: RetryPolicy,
        concurrency: usize,
        _buffer_size: usize,
    ) -> Self {
        Self {
            provider,
            retry_policy,
            plugin_pipeline: None,
            semaphore: Arc::new(Semaphore::new(concurrency)),
            closing: AtomicBool::new(false),
        }
    }

    pub fn with_plugins(
        mut self,
        pipeline: PluginPipeline,
    ) -> Self {
        self.plugin_pipeline = Some(pipeline);
        self
    }

    pub async fn send(&self, request: GatewayRequest) -> Result<GatewayResponse, GatewayError> {
        if self.closing.load(Ordering::SeqCst) {
            return Err(GatewayError::new(
                crate::error::ErrorKind::NoProviderAvailable,
                "Queue is closing",
            ));
        }

        let (response_tx, response_rx) = oneshot::channel();

        let permit = self.semaphore.clone().acquire_owned().await.map_err(|_| {
            GatewayError::new(
                crate::error::ErrorKind::NoProviderAvailable,
                "Queue is closed",
            )
        })?;

        let provider = self.provider.clone();
        let retry_policy = RetryPolicy::new(
            self.retry_policy.max_retries(),
            self.retry_policy.backoff_initial(),
            self.retry_policy.backoff_max(),
        );
        let plugin_pipeline = self.plugin_pipeline.clone();

        tokio::spawn(async move {
            let response = process_request(&provider, &retry_policy, &plugin_pipeline, request).await;
            let _ = response_tx.send(response);
            drop(permit);
        });

        response_rx.await.map_err(|_| {
            GatewayError::new(
                crate::error::ErrorKind::NoProviderAvailable,
                "Request cancelled",
            )
        })
    }

    pub async fn shutdown(&self) {
        self.closing.store(true, Ordering::SeqCst);
    }

    pub fn is_closing(&self) -> bool {
        self.closing.load(Ordering::SeqCst)
    }
}

async fn process_request(
    provider: &ProviderRef,
    retry_policy: &RetryPolicy,
    plugin_pipeline: &Option<PluginPipeline>,
    mut request: GatewayRequest,
) -> GatewayResponse {
    // Execute pre-request hooks
    if let Some(pipeline) = plugin_pipeline {
        if let Err(e) = pipeline.execute_pre_request(&mut request).await {
            return GatewayResponse::ChatCompletion(Err(e));
        }
    }

    let mut response = match request {
        GatewayRequest::ChatCompletion(req) => {
            let result = retry_policy
                .execute(|| async { provider.chat_completion(req.clone()).await })
                .await;
            GatewayResponse::ChatCompletion(result)
        }
        GatewayRequest::Embedding(req) => {
            let result = retry_policy
                .execute(|| async { provider.embedding(req.clone()).await })
                .await;
            GatewayResponse::Embedding(result)
        }
        GatewayRequest::ListModels => {
            let result = retry_policy
                .execute(|| async { provider.list_models().await })
                .await;
            GatewayResponse::ListModels(result)
        }
    };

    // Execute post-response hooks
    if let Some(pipeline) = plugin_pipeline {
        if let Err(e) = pipeline.execute_post_response(&mut response).await {
            return GatewayResponse::ChatCompletion(Err(e));
        }
    }

    response
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_queue_creation() {
        let semaphore = Semaphore::new(10);
        assert_eq!(semaphore.available_permits(), 10);
    }
}
