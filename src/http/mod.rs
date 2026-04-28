use actix_web::{web, App, HttpServer};
use std::sync::Arc;
use tokio::sync::oneshot;

use crate::config::AppConfig;
use crate::gateway::Gateway;
use crate::mcp::agent::AgentExecutor;
use crate::mcp::client::MCPClientManager;

pub mod auth;
pub mod handlers;
pub mod metrics;
pub mod middleware;

pub struct ServerHandle {
    shutdown_tx: oneshot::Sender<()>,
}

impl ServerHandle {
    pub fn shutdown(self) {
        let _ = self.shutdown_tx.send(());
    }
}

pub async fn run_server(
    config: &AppConfig,
    gateway: Arc<Gateway>,
    mcp_manager: Option<Arc<MCPClientManager>>,
    agent_executor: Option<Arc<AgentExecutor>>,
) -> std::io::Result<ServerHandle> {
    let gateway = web::Data::new(gateway);
    let mcp_manager = web::Data::new(mcp_manager);
    let agent_executor = web::Data::new(agent_executor);
    let bind_addr = format!("{}:{}", config.server.host, config.server.port);
    let allowed_origins = config.server.allowed_origins.clone();
    let max_body_size = config.server.max_body_size;
    let api_keys = config.server.api_keys.clone();

    let (shutdown_tx, shutdown_rx) = oneshot::channel();

    let metrics = metrics::Metrics::new();
    let server = HttpServer::new(move || {
        App::new()
            .wrap(actix_web::middleware::Logger::default())
            .wrap(middleware::configure_cors(&allowed_origins))
            .wrap(middleware::RequestIdMiddleware)
            .wrap(auth::ApiKeyAuth::new(api_keys.clone()))
            .app_data(gateway.clone())
            .app_data(metrics.clone())
            .app_data(mcp_manager.clone())
            .app_data(agent_executor.clone())
            .app_data(web::PayloadConfig::new(max_body_size))
            .app_data(web::JsonConfig::default().limit(max_body_size))
            .route(
                "/v1/chat/completions",
                web::post().to(handlers::chat_completions),
            )
            .route("/v1/embeddings", web::post().to(handlers::embeddings))
            .route("/v1/models", web::get().to(handlers::list_models))
            .route("/health", web::get().to(handlers::health))
            .route("/metrics", web::get().to(handlers::metrics_endpoint))
    })
    .bind(&bind_addr)?
    .run();

    let server_handle = server.handle();

    tokio::spawn(async move {
        let _ = shutdown_rx.await;
        server_handle.stop(true).await;
    });

    tokio::spawn(server);

    Ok(ServerHandle { shutdown_tx })
}
