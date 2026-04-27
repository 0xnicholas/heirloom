use tokio::sync::{broadcast, mpsc, oneshot};
use tokio::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use crate::error::GatewayError;
use crate::types::*;
use crate::provider::ProviderRef;
use super::retry::RetryPolicy;

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

pub struct QueuedRequest {
    pub request: GatewayRequest,
    pub response_tx: oneshot::Sender<GatewayResponse>,
}

pub struct ProviderQueue {
    tx: mpsc::Sender<QueuedRequest>,
    shutdown_tx: broadcast::Sender<()>,
    closing: AtomicBool,
}

impl ProviderQueue {
    pub fn new(
        provider: ProviderRef,
        retry_policy: RetryPolicy,
        concurrency: usize,
        buffer_size: usize,
    ) -> Self {
        let (tx, rx) = mpsc::channel::<QueuedRequest>(buffer_size);
        let rx = Arc::new(Mutex::new(rx));
        let (shutdown_tx, _) = broadcast::channel(1);
        
        for _ in 0..concurrency {
            let provider = provider.clone();
            let retry_policy = RetryPolicy::new(
                retry_policy.max_retries(),
                retry_policy.backoff_initial(),
                retry_policy.backoff_max(),
            );
            let rx = rx.clone();
            let mut shutdown_rx = shutdown_tx.subscribe();
            
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        Some(req) = async { rx.lock().await.recv().await } => {
                            let response = process_request(&provider, &retry_policy, req.request).await;
                            let _ = req.response_tx.send(response);
                        }
                        _ = shutdown_rx.recv() => {
                            // Drain remaining requests
                            let mut rx = rx.lock().await;
                            while let Some(req) = rx.try_recv().ok() {
                                let response = process_request(&provider, &retry_policy, req.request).await;
                                let _ = req.response_tx.send(response);
                            }
                            break;
                        }
                    }
                }
            });
        }
        
        Self {
            tx,
            shutdown_tx,
            closing: AtomicBool::new(false),
        }
    }
    
    pub async fn send(
        &self,
        request: GatewayRequest,
    ) -> Result<GatewayResponse, GatewayError> {
        if self.closing.load(Ordering::SeqCst) {
            return Err(GatewayError::new(
                crate::error::ErrorKind::NoProviderAvailable,
                "Queue is closing"
            ));
        }
        
        let (response_tx, response_rx) = oneshot::channel();
        
        self.tx.send(QueuedRequest { request, response_tx })
            .await
            .map_err(|_| GatewayError::new(
                crate::error::ErrorKind::NoProviderAvailable,
                "Queue is full or closed"
            ))?;
        
        response_rx.await
            .map_err(|_| GatewayError::new(
                crate::error::ErrorKind::NoProviderAvailable,
                "Request cancelled"
            ))
    }
    
    pub async fn shutdown(&self) {
        if self.closing.swap(true, Ordering::SeqCst) {
            return; // Already closing
        }
        let _ = self.shutdown_tx.send(());
    }
    
    pub fn is_closing(&self) -> bool {
        self.closing.load(Ordering::SeqCst)
    }
}

async fn process_request(
    provider: &ProviderRef,
    retry_policy: &RetryPolicy,
    request: GatewayRequest,
) -> GatewayResponse {
    match request {
        GatewayRequest::ChatCompletion(req) => {
            let result = retry_policy.execute(|| async {
                provider.chat_completion(req.clone()).await
            }).await;
            GatewayResponse::ChatCompletion(result)
        }
        GatewayRequest::Embedding(req) => {
            let result = retry_policy.execute(|| async {
                provider.embedding(req.clone()).await
            }).await;
            GatewayResponse::Embedding(result)
        }
        GatewayRequest::ListModels => {
            let result = retry_policy.execute(|| async {
                provider.list_models().await
            }).await;
            GatewayResponse::ListModels(result)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn test_queue_creation() {
        let (_tx, _rx) = mpsc::channel::<QueuedRequest>(10);
        assert_eq!(_tx.capacity(), 10);
    }
}
