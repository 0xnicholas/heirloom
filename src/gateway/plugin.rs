use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use crate::error::GatewayError;
use crate::gateway::queue::{GatewayRequest, GatewayResponse};
use crate::types::*;

/// Plugin hook point for request/response processing
#[async_trait::async_trait]
pub trait Plugin: Send + Sync {
    /// Plugin name
    fn name(&self) -> &'static str;

    /// Called before request is sent to provider
    async fn pre_request(&self,
        request: &mut GatewayRequest,
    ) -> Result<(), GatewayError> {
        let _ = request;
        Ok(())
    }

    /// Called after response is received from provider
    async fn post_response(&self,
        response: &mut GatewayResponse,
    ) -> Result<(), GatewayError> {
        let _ = response;
        Ok(())
    }
}

/// Plugin pipeline that executes plugins in order
#[derive(Clone)]
pub struct PluginPipeline {
    plugins: Vec<Arc<dyn Plugin>>,
}

impl PluginPipeline {
    pub fn new() -> Self {
        Self {
            plugins: Vec::new(),
        }
    }

    pub fn add_plugin(&mut self,
        plugin: Arc<dyn Plugin>,
    ) {
        self.plugins.push(plugin);
    }

    pub async fn execute_pre_request(
        &self,
        request: &mut GatewayRequest,
    ) -> Result<(), GatewayError> {
        for plugin in &self.plugins {
            plugin.pre_request(request).await?;
        }
        Ok(())
    }

    pub async fn execute_post_response(
        &self,
        response: &mut GatewayResponse,
    ) -> Result<(), GatewayError> {
        for plugin in &self.plugins {
            plugin.post_response(response).await?;
        }
        Ok(())
    }
}

impl Default for PluginPipeline {
    fn default() -> Self {
        Self::new()
    }
}

/// Example: Logging plugin
pub struct LoggingPlugin;

#[async_trait::async_trait]
impl Plugin for LoggingPlugin {
    fn name(&self) -> &'static str {
        "logging"
    }

    async fn pre_request(
        &self,
        request: &mut GatewayRequest,
    ) -> Result<(), GatewayError> {
        match request {
            GatewayRequest::ChatCompletion(req) => {
                tracing::info!(
                    "[plugin:logging] Chat request: model={}",
                    req.model
                );
            }
            GatewayRequest::Embedding(req) => {
                tracing::info!(
                    "[plugin:logging] Embedding request: model={}",
                    req.model
                );
            }
            GatewayRequest::ListModels => {
                tracing::info!("[plugin:logging] List models request");
            }
        }
        Ok(())
    }
}

/// Example: Request transformation plugin
pub struct TransformPlugin;

#[async_trait::async_trait]
impl Plugin for TransformPlugin {
    fn name(&self) -> &'static str {
        "transform"
    }

    async fn pre_request(
        &self,
        request: &mut GatewayRequest,
    ) -> Result<(), GatewayError> {
        if let GatewayRequest::ChatCompletion(req) = request {
            // Add system message if not present
            let has_system = req.messages.iter().any(|m| matches!(m, ChatMessage::System { .. }));
            if !has_system {
                req.messages.insert(
                    0,
                    ChatMessage::System {
                        content: "You are a helpful assistant.".to_string(),
                    },
                );
            }
        }
        Ok(())
    }
}